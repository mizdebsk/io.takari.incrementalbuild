package io.takari.incrementalbuild.spi;

import static io.takari.incrementalbuild.spi.MMaps.put;
import io.takari.incrementalbuild.MessageSeverity;
import io.takari.incrementalbuild.ResourceMetadata;
import io.takari.incrementalbuild.ResourceStatus;
import io.takari.incrementalbuild.workspace.Workspace;
import io.takari.incrementalbuild.workspace.Workspace.FileVisitor;
import io.takari.incrementalbuild.workspace.Workspace.Mode;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks build input and output resources and associations among them.
 */
public abstract class AbstractBuildContext {
  protected final Logger log = LoggerFactory.getLogger(getClass());

  private final Workspace workspace;

  private final File stateFile;

  private final DefaultBuildContextState state;

  private final DefaultBuildContextState oldState;

  /**
   * Previous build state does not exist, cannot be read or configuration has changed. When
   * escalated, all input files are considered require processing.
   */
  private final boolean escalated;

  /**
   * Indicates that no further modifications to this build context are allowed.
   */
  private boolean closed;

  /**
   * Resources known to be deleted since previous build. Includes both resources reported as deleted
   * by Workspace and resources explicitly delete through this build context.
   */
  private final Set<File> deletedResources = new HashSet<>();

  /**
   * Resources selected for processing during this build. This includes resources created, changed
   * and deleted through this build context.
   */
  private final Set<Object> processedResources = new HashSet<>();

  public AbstractBuildContext(Workspace workspace, File stateFile,
      Map<String, Serializable> configuration) {

    // preconditions
    if (workspace == null) {
      throw new NullPointerException();
    }
    if (configuration == null) {
      throw new NullPointerException();
    }

    this.stateFile = stateFile;
    this.state = DefaultBuildContextState.withConfiguration(configuration);
    this.oldState = DefaultBuildContextState.loadFrom(stateFile);

    final boolean configurationChanged = getConfigurationChanged();
    if (workspace.getMode() == Mode.ESCALATED) {
      this.escalated = true;
      this.workspace = workspace;
    } else if (workspace.getMode() == Mode.SUPPRESSED) {
      this.escalated = false;
      this.workspace = workspace;
    } else if (configurationChanged) {
      this.escalated = true;
      this.workspace = workspace.escalate();
    } else {
      this.escalated = false;
      this.workspace = workspace;
    }

    if (escalated && stateFile != null) {
      if (!stateFile.canRead()) {
        log.info("Previous incremental build state does not exist, performing full build");
      } else {
        log.info("Incremental build configuration change detected, performing full build");
      }
    } else {
      log.info("Performing incremental build");
    }
  }

  private boolean getConfigurationChanged() {
    Map<String, Serializable> configuration = state.configuration;
    Map<String, Serializable> oldConfiguration = oldState.configuration;

    if (oldConfiguration.isEmpty()) {
      return true; // no previous state
    }

    Set<String> keys = new TreeSet<String>();
    keys.addAll(configuration.keySet());
    keys.addAll(oldConfiguration.keySet());

    boolean result = false;
    StringBuilder msg = new StringBuilder();

    for (String key : keys) {
      Serializable value = configuration.get(key);
      Serializable oldValue = oldConfiguration.get(key);
      if (!Objects.equals(oldValue, value)) {
        result = true;
        msg.append("\n   ");
        if (value == null) {
          msg.append("REMOVED");
        } else if (oldValue == null) {
          msg.append("ADDED");
        } else {
          msg.append("CHANGED");
        }
        msg.append(' ').append(key);
      }
    }

    if (result) {
      log.debug("Incremental build configuration key changes:{}", msg.toString());
    }

    return result;
  }

  public boolean isEscalated() {
    return escalated;
  }

  /**
   * Registers matching resources as this build's input set.
   */
  public Collection<DefaultResourceMetadata<File>> registerInputs(File basedir,
      Collection<String> includes, Collection<String> excludes) throws IOException {
    basedir = normalize(basedir);
    final List<DefaultResourceMetadata<File>> result = new ArrayList<>();
    final FileMatcher matcher = FileMatcher.matcher(basedir, includes, excludes);
    workspace.walk(basedir, new FileVisitor() {
      @Override
      public void visit(File file, long lastModified, long length, Workspace.ResourceStatus status) {
        if (matcher.matches(file)) {
          switch (status) {
            case MODIFIED:
            case NEW:
              result.add(registerNormalizedResource(file, lastModified, length, false));
              break;
            case REMOVED:
              deletedResources.add(file);
              break;
            default:
              throw new IllegalArgumentException();
          }
        }
      }
    });
    if (workspace.getMode() == Mode.DELTA) {
      // only NEW, MODIFIED and REMOVED resources are reported in DELTA mode
      // need to find any UNMODIFIED
      final FileMatcher absoluteMatcher = FileMatcher.matcher(basedir, includes, excludes);
      for (ResourceHolder<?> holder : oldState.resources.values()) {
        if (holder instanceof FileState) {
          FileState fileState = (FileState) holder;
          if (!state.resources.containsKey(fileState.file)
              && !deletedResources.contains(fileState.file)
              && absoluteMatcher.matches(fileState.file)) {
            result.add(registerNormalizedResource(fileState.file, fileState.lastModified,
                fileState.length, false));
          }
        }
      }
    }
    return result;
  }

  protected static File normalize(File file) {
    if (file == null) {
      throw new IllegalArgumentException();
    }
    try {
      return file.getCanonicalFile();
    } catch (IOException e) {
      return file.getAbsoluteFile();
    }
  }

  private DefaultResourceMetadata<File> registerNormalizedResource(File resourceFile,
      long lastModified, long length, boolean replace) {
    if (!state.resources.containsKey(resourceFile)) {
      registerResource(newFileState(resourceFile, lastModified, length), replace);
    }
    return new DefaultResourceMetadata<File>(this, oldState, resourceFile);
  }

  private FileState newFileState(File file, long lastModified, long length) {
    if (!workspace.isPresent(file)) {
      throw new IllegalArgumentException("File does not exist or cannot be read " + file);
    }
    return new FileState(file, lastModified, length);
  }

  /**
   * Adds the resource to this build's resource set. The resource must exist, i.e. it's status must
   * not be REMOVED.
   */
  private <T extends Serializable> T registerResource(ResourceHolder<T> holder, boolean replace) {
    T resource = holder.getResource();
    ResourceHolder<?> other = state.resources.get(resource);
    if (other == null) {
      if (getResourceStatus(holder) == ResourceStatus.REMOVED) {
        throw new IllegalArgumentException("Resource does not exist " + resource);
      }
      state.resources.put(resource, holder);
    } else {
      if (!holder.equals(other)) {
        if (!replace) {
          throw new IllegalArgumentException("Inconsistent resource state " + resource);
        }
        state.resources.put(resource, holder);
      }
    }
    return resource;
  }

  /**
   * Returns resource status compared to the previous build.
   */
  public ResourceStatus getResourceStatus(Object resource) {
    if (deletedResources.contains(resource)) {
      return ResourceStatus.REMOVED;
    }

    ResourceHolder<?> oldResourceState = oldState.resources.get(resource);
    if (oldResourceState == null) {
      return ResourceStatus.NEW;
    }

    if (escalated) {
      return ResourceStatus.MODIFIED;
    }

    return getResourceStatus(oldResourceState);
  }

  private ResourceStatus getResourceStatus(ResourceHolder<?> holder) {
    if (holder instanceof FileState) {
      FileState fileState = (FileState) holder;
      switch (workspace.getResourceStatus(fileState.file, fileState.lastModified, fileState.length)) {
        case NEW:
          return ResourceStatus.NEW;
        case MODIFIED:
          return ResourceStatus.MODIFIED;
        case REMOVED:
          return ResourceStatus.REMOVED;
        case UNMODIFIED:
          return ResourceStatus.UNMODIFIED;
      }
      throw new IllegalArgumentException();
    }
    return holder.getStatus();
  }

  public <T> DefaultResource<T> processResource(DefaultResourceMetadata<T> metadata) {
    final T resource = metadata.getResource();

    if (metadata.context != this || !state.resources.containsKey(resource)) {
      throw new IllegalArgumentException();
    }

    if (metadata instanceof DefaultResource) {
      return (DefaultResource<T>) metadata;
    }

    processResource(resource);

    return new DefaultResource<T>(this, state, resource);
  }

  private <T> void processResource(final T resource) {
    processedResources.add(resource);

    // reset all metadata associated with the resource during this build
    state.resourceAttributes.remove(resource);
    state.resourceMessages.remove(resource);
    state.resourceOutputs.remove(resource);
  }

  // simple key/value pairs

  public <T extends Serializable> Serializable setResourceAttribute(Object resource, String key,
      T value) {
    Map<String, Serializable> attributes = state.resourceAttributes.get(resource);
    if (attributes == null) {
      attributes = new LinkedHashMap<String, Serializable>();
      state.resourceAttributes.put(resource, attributes);
    }
    attributes.put(key, value);
    Map<String, Serializable> oldAttributes = oldState.resourceAttributes.get(resource);
    return oldAttributes != null ? (Serializable) oldAttributes.get(key) : null;
  }

  public <T extends Serializable> T getResourceAttribute(DefaultBuildContextState state,
      Object resource, String key, Class<T> clazz) {
    Map<String, Serializable> attributes = state.resourceAttributes.get(resource);
    return attributes != null ? clazz.cast(attributes.get(key)) : null;
  }

  // persisted messages

  public void addMessage(Object resource, int line, int column, String message,
      MessageSeverity severity, Throwable cause) {
    // this is likely called as part of builder error handling logic.
    // to make IAE easier to troubleshoot, link cause to the exception thrown
    if (resource == null) {
      throw new IllegalArgumentException(cause);
    }
    if (severity == null) {
      throw new IllegalArgumentException(cause);
    }
    put(state.resourceMessages, resource, new Message(line, column, message, severity, cause));
    log(resource, line, column, message, severity, cause);
  }

  public DefaultOutput processOutput(File outputFile) {
    outputFile = normalize(outputFile);

    registerNormalizedResource(outputFile, outputFile.lastModified(), outputFile.length(), true);
    processResource(outputFile);
    state.outputs.add(outputFile);

    return new DefaultOutput(this, state, outputFile);
  }

  public OutputStream newOutputStream(DefaultOutput output) throws IOException {
    return workspace.newOutputStream(output.getResource());
  }

  public <T> void associate(DefaultResource<T> resource, DefaultOutput output) {
    if (resource.context != this) {
      throw new IllegalArgumentException();
    }
    if (output.context != this) {
      throw new IllegalArgumentException();
    }

    put(state.resourceOutputs, resource.getResource(), output.getResource());
  }

  public Collection<? extends ResourceMetadata<File>> getAssociatedOutputs(
      DefaultBuildContextState state, Object resource) {
    Collection<File> outputFiles = state.resourceOutputs.get(resource);
    if (outputFiles == null || outputFiles.isEmpty()) {
      return Collections.emptyList();
    }
    List<ResourceMetadata<File>> outputs = new ArrayList<>();
    for (File outputFile : outputFiles) {
      outputs.add(new DefaultResourceMetadata<File>(this, state, outputFile));
    }
    return outputs;
  }

  public void commit(MessageSinkAdaptor messager) throws IOException {
    if (closed) {
      return;
    }
    this.closed = true;

    Map<Object, Collection<Message>> newMessages = new HashMap<>(state.resourceMessages);

    // carry-over up-to-date resources
    for (Object resource : oldState.resources.keySet()) {
      if (processedResources.contains(resource) || deletedResources.contains(resource)) {
        // known deleted or processed resource, nothing to carry over
        continue;
      }

      ResourceHolder<?> holder = state.resources.get(resource);

      if (holder == null) {
        if (oldState.outputs.contains(resource)) {
          // old output resource that was not re-processed or deleted during this build
          if (!isOutputUptodate((File) resource)) {
            // state or orphaned output, delete and do not carry-over metadata
            deleteOutput((File) resource);
            continue;
          }
          // else, carry-over output resource metadata
          holder = oldState.resources.get(resource);
        } else {
          // old input that was not re-registered during this build, do not carry-over metadata
          continue;
        }
      }

      state.resources.put(resource, holder);

      // carry-over messages
      Collection<Message> messages = oldState.resourceMessages.get(resource);
      if (messages != null && !messages.isEmpty()) {
        state.resourceMessages.put(resource, messages);
      }

      // carry-over attributes
      Map<String, Serializable> attributes = oldState.resourceAttributes.get(resource);
      if (attributes != null && !attributes.isEmpty()) {
        state.resourceAttributes.put(resource, attributes);
      }

      Collection<File> outputFiles = oldState.resourceOutputs.get(resource);
      if (outputFiles != null && !outputFiles.isEmpty()) {
        state.resourceOutputs.put(resource, outputFiles);
      }
    }

    if (stateFile != null) {
      final long start = System.currentTimeMillis();
      try (OutputStream os = workspace.newOutputStream(stateFile)) {
        state.storeTo(os);
      }
      log.debug("Stored incremental build state {} ({} ms)", stateFile, System.currentTimeMillis()
          - start);
    }

    // new messages are logged as soon as they are reported during the build
    // replay old messages so the user can still see them
    Map<Object, Collection<Message>> allMessages = new HashMap<>(state.resourceMessages);
    if (!allMessages.keySet().equals(newMessages.keySet())) {
      log.info("Replaying recorded messages...");
      for (Map.Entry<Object, Collection<Message>> entry : allMessages.entrySet()) {
        Object resource = entry.getKey();
        if (!newMessages.containsKey(resource)) {
          for (Message message : entry.getValue()) {
            log(resource, message.line, message.column, message.message, message.severity,
                message.cause);
          }
        }
      }
    }

    // processedResources includes resources added, changed and deleted during this build
    // clear all old messages associated with the processed resources during previous builds
    for (Object resource : processedResources) {
      messager.clear(resource);
    }
    messager.record(allMessages, newMessages);

    // XXX this is wrong, need to delegate through messager some how
    // without messageSink, have to raise exception if there were errors
    int errorCount = 0;
    StringBuilder errors = new StringBuilder();
    for (Map.Entry<Object, Collection<Message>> entry : newMessages.entrySet()) {
      Object resource = entry.getKey();
      for (Message message : entry.getValue()) {
        if (message.severity == MessageSeverity.ERROR) {
          errorCount++;
          errors.append(String.format("%s:[%d:%d] %s\n", resource.toString(), message.line,
              message.column, message.message));
        }
      }
    }

  }

  protected abstract boolean isOutputUptodate(File resource);

  protected void log(Object resource, int line, int column, String message,
      MessageSeverity severity, Throwable cause) {
    switch (severity) {
      case ERROR:
        log.error("{}:[{}:{}] {}", resource.toString(), line, column, message, cause);
        break;
      case WARNING:
        log.warn("{}:[{}:{}] {}", resource.toString(), line, column, message, cause);
        break;
      default:
        log.info("{}:[{}:{}] {}", resource.toString(), line, column, message, cause);
        break;
    }
  }

  public void deleteOutput(File resource) throws IOException {
    if (!oldState.outputs.contains(resource) && !state.outputs.contains(resource)) {
      // not an output known to this build context
      throw new IllegalArgumentException();
    }

    workspace.deleteFile(resource);

    deletedResources.add(resource);
    processedResources.add(resource);

    state.resources.remove(resource);
    state.outputs.remove(resource);

    state.resourceAttributes.remove(resource);
    state.resourceMessages.remove(resource);
    state.resourceOutputs.remove(resource);
  }

  protected void assertOpen() {
    if (closed) {
      throw new IllegalStateException();
    }
  }

  /**
   * Marks skipped build execution. All inputs, outputs and their associated metadata are carried
   * over to the next build as-is. No context modification operations (register* or process) are
   * permitted after this call.
   */
  public void markSkipExecution() {
    if (!processedResources.isEmpty()) {
      throw new IllegalStateException();
    }

    closed = true;
  }
}