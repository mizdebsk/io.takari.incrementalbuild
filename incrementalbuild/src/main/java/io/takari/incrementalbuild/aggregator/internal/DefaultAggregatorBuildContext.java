package io.takari.incrementalbuild.aggregator.internal;


import static io.takari.incrementalbuild.spi.MMaps.put;
import io.takari.incrementalbuild.Resource;
import io.takari.incrementalbuild.ResourceMetadata;
import io.takari.incrementalbuild.ResourceStatus;
import io.takari.incrementalbuild.aggregator.AggregateCreator;
import io.takari.incrementalbuild.aggregator.AggregateInput;
import io.takari.incrementalbuild.aggregator.AggregatorBuildContext;
import io.takari.incrementalbuild.aggregator.InputProcessor;
import io.takari.incrementalbuild.spi.AbstractBuildContext;
import io.takari.incrementalbuild.spi.BuildContextEnvironment;
import io.takari.incrementalbuild.spi.DefaultOutput;
import io.takari.incrementalbuild.spi.DefaultResource;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DefaultAggregatorBuildContext extends AbstractBuildContext
    implements
      AggregatorBuildContext {

  private final Map<File, File> inputBasedir = new HashMap<>();

  private final Map<File, Collection<File>> outputInputs = new HashMap<>();

  public DefaultAggregatorBuildContext(BuildContextEnvironment configuration) {
    super(configuration);
  }

  @Override
  public DefaultAggregateMetadata registerOutput(File outputFile) {
    outputFile = normalize(outputFile);
    // TODO throw IllegaleStateException if the output has already been generated
    registerNormalizedOutput(outputFile);
    return new DefaultAggregateMetadata(this, oldState, outputFile);
  }

  @Override
  protected boolean shouldCarryOverOutput(File resource) {
    return false; // delete outputs that were not recreated during this build
  }

  public void associatedInputs(DefaultAggregateMetadata output, File basedir,
      Collection<String> includes, Collection<String> excludes, InputProcessor... processors)
      throws IOException {
    basedir = normalize(basedir);
    for (ResourceMetadata<File> inputMetadata : registerInputs(basedir, includes, excludes)) {
      inputBasedir.put(inputMetadata.getResource(), basedir);
      if (inputMetadata.getStatus() != ResourceStatus.UNMODIFIED) {
        Resource<File> input = inputMetadata.process();
        if (processors != null) {
          for (InputProcessor processor : processors) {
            processor.process(input);
          }
        }
      }
      put(outputInputs, output.getResource(), inputMetadata.getResource());
    }
  }

  public boolean createIfNecessary(DefaultAggregateMetadata outputMetadata, AggregateCreator creator)
      throws IOException {
    File outputFile = outputMetadata.getResource();
    boolean processingRequired = getResourceStatus(outputFile) != ResourceStatus.UNMODIFIED;
    Collection<File> inputFiles = outputInputs.get(outputMetadata.getResource());
    if (!processingRequired && inputFiles != null) {
      for (File input : inputFiles) {
        if (getResourceStatus(input) != ResourceStatus.UNMODIFIED) {
          processingRequired = true;
          break;
        }
      }
    }
    if (processingRequired) {
      DefaultOutput output = processOutput(outputFile);
      List<AggregateInput> inputs = new ArrayList<>();
      if (inputFiles != null) {
        for (File inputFile : inputFiles) {
          if (!isProcessedResource(inputFile)) {
            processResource(inputFile);
          }
          state.putOutputInput(outputFile, inputFile);
          inputs
              .add(new DefaultAggregateInput(this, state, inputBasedir.get(inputFile), inputFile));
        }
      }
      creator.create(output, inputs);
    } else {
      markUptodateOutput(outputFile);
    }
    return processingRequired;
  }

  @Override
  protected void assertAssociation(DefaultResource<?> resource, DefaultOutput output) {
    // aggregating context supports any association between inputs and outputs
    // or, rather, there is no obviously wrong combination
  }
}
