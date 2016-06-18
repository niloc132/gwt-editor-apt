package de.gishmo.gwt.editor.processor;

import com.google.auto.common.BasicAnnotationProcessor;
import com.google.auto.service.AutoService;
import com.google.common.collect.ImmutableList;

import javax.annotation.processing.Processor;
import javax.lang.model.SourceVersion;

/**
 * Created by colin on 7/17/16.
 */
@AutoService(Processor.class)
public class DriverProcessor extends BasicAnnotationProcessor {

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  @Override
  protected Iterable<? extends ProcessingStep> initSteps() {
    return ImmutableList.of(new DriverProcessingStep.Builder()
            .setMessager(processingEnv.getMessager())
            .setFiler(processingEnv.getFiler())
            .setTypes(processingEnv.getTypeUtils())
            .setElements(processingEnv.getElementUtils())
            .build());
  }
}
