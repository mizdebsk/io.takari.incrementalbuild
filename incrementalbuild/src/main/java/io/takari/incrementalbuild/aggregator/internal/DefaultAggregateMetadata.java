package io.takari.incrementalbuild.aggregator.internal;

import io.takari.incrementalbuild.aggregator.AggregateCreator;
import io.takari.incrementalbuild.aggregator.AggregateOutput;
import io.takari.incrementalbuild.aggregator.InputProcessor;
import io.takari.incrementalbuild.aggregator.MetadataAggregateCreator;
import io.takari.incrementalbuild.spi.DefaultBuildContextState;
import io.takari.incrementalbuild.spi.DefaultResourceMetadata;

import java.io.File;
import java.io.IOException;
import java.util.Collection;

public class DefaultAggregateMetadata extends DefaultResourceMetadata<File>
    implements
      AggregateOutput {

  DefaultAggregateMetadata(DefaultAggregatorBuildContext context, DefaultBuildContextState state,
      File file) {
    super(context, state, file);
  }

  @Override
  protected DefaultAggregatorBuildContext getContext() {
    return (DefaultAggregatorBuildContext) super.getContext();
  }

  @Override
  public void addInputs(File basedir, Collection<String> includes, Collection<String> excludes,
      InputProcessor... processors) throws IOException {
    getContext().associateInputs(this, basedir, includes, excludes, processors);
  }

  @Override
  public boolean createIfNecessary(AggregateCreator creator) throws IOException {
    return getContext().createIfNecessary(this, creator);
  }

  @Override
  public boolean createIfNecessary(MetadataAggregateCreator creator) throws IOException {
    return getContext().createIfNecessary(this, creator);
  }
}