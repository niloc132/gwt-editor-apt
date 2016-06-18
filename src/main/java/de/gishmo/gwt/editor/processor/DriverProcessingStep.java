package de.gishmo.gwt.editor.processor;

import com.google.auto.common.BasicAnnotationProcessor.ProcessingStep;
import com.google.auto.common.MoreTypes;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;
import com.google.gwt.editor.client.EditorVisitor;
import com.google.gwt.editor.client.SimpleBeanEditorDriver;
import com.google.gwt.editor.client.impl.AbstractEditorDelegate;
import com.google.gwt.editor.client.impl.AbstractSimpleBeanEditorDriver;
import com.google.gwt.editor.client.impl.RootEditorContext;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import de.gishmo.gwt.editor.client.annotation.IsDriver;
import de.gishmo.gwt.editor.processor.model.EditorModel;
import de.gishmo.gwt.editor.processor.model.EditorTypes;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Created by colin on 7/17/16.
 */
public class DriverProcessingStep implements ProcessingStep {

  private final Messager messager;
  private final Filer filer;
  private final Types types;
  private final Elements elements;

  private DriverProcessingStep(Messager messager, Filer filer, Types types, Elements elements) {
    this.messager = messager;
    this.filer = filer;
    this.types = types;
    this.elements = elements;
  }

  public static class Builder {
    private Messager messager;
    private Filer filer;
    private Types types;
    private Elements elements;

    public Builder setMessager(Messager messager) {
      this.messager = messager;
      return this;
    }

    public Builder setFiler(Filer filer) {
      this.filer = filer;
      return this;
    }

    public Builder setTypes(Types types) {
      this.types = types;
      return this;
    }

    public Builder setElements(Elements elements) {
      this.elements = elements;
      return this;
    }

    public DriverProcessingStep build() {
      return new DriverProcessingStep(messager, filer, types, elements);
    }
  }

  @Override
  public Set<? extends Class<? extends Annotation>> annotations() {
    return Collections.singleton(IsDriver.class);
  }

  @Override
  public Set<Element> process(SetMultimap<Class<? extends Annotation>, Element> elementsByAnnotation) {

    for (Element element : elementsByAnnotation.get(IsDriver.class)) {
      EditorModel root = new EditorModel(messager, new EditorTypes(types, elements), element.asType(), types.erasure(elements.getTypeElement(getDriverType().getName()).asType()));

      //TODO watch for messages and give up if we see errors, return that rather than empty set
      try {
        generateDriver((TypeElement)element, root);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    return ImmutableSet.of();
  }

  protected Class<SimpleBeanEditorDriver> getDriverType() {
    return SimpleBeanEditorDriver.class;
  }

  private void generateDriver(TypeElement interfaceToImplement, EditorModel rootEditorModel) throws IOException {
    //start driver
    String pkgName = elements.getPackageOf(interfaceToImplement).toString();

    String typeName = createNameFromEnclosedTypes(interfaceToImplement, "_Impl");
    JavaFileObject driverJfo = filer.createSourceFile(pkgName + "." + typeName);
    try (Writer writer = driverJfo.openWriter()) {



    //impl accept(visitor) method
      ParameterizedTypeName rootEdContextType = ParameterizedTypeName.get(ClassName.get(RootEditorContext.class), TypeName.get(rootEditorModel.getProxyType()));
      MethodSpec accept = MethodSpec.methodBuilder("accept")
              .addModifiers(Modifier.PUBLIC)
              .returns(void.class)
              .addAnnotation(Override.class)
              .addParameter(EditorVisitor.class, "visitor")
              //ugly cast to shut up java warnings at compile time - however, this might be overkill, could just use raw types
              .addStatement("$T ctx = new $T(getDelegate(), (Class<$T>)(Class)$L.class, getObject())",
                      rootEdContextType,
                      rootEdContextType,
                      TypeName.get(rootEditorModel.getProxyType()),
                      MoreTypes.asElement(rootEditorModel.getProxyType()))
              .addStatement("ctx.traverse(visitor, getDelegate())")
              .build();


    //impl createDelegate() method
    // - lazily building the delegate type if require (this is recursive), see com.google.gwt.editor.rebind.AbstractEditorDriverGenerator.getEditorDelegate()
    // - build context
    // - break out various impl methods to allow custom EditorDriver subtypes like RFED
      ParameterizedTypeName delegateType = ParameterizedTypeName.get(ClassName.get(AbstractEditorDelegate.class),  TypeName.get(rootEditorModel.getProxyType()), TypeName.get(rootEditorModel.getEditorType()));
      MethodSpec createDelegate = MethodSpec.methodBuilder("createDelegate")
              .addModifiers(Modifier.PROTECTED)
              .returns(delegateType)
              .addAnnotation(Override.class)
              .addStatement("return null")//TODO
              .build();

    //implement interface, extend BaseEditorDriver or whatnot
      elements.getTypeElement(AbstractSimpleBeanEditorDriver.class.getCanonicalName());

      TypeSpec driverType = TypeSpec.classBuilder(typeName)
              .addModifiers(Modifier.PUBLIC)
              .addSuperinterface(TypeName.get(interfaceToImplement.asType()))
              .superclass(ParameterizedTypeName.get(ClassName.get(AbstractSimpleBeanEditorDriver.class), TypeName.get(rootEditorModel.getProxyType()), TypeName.get(rootEditorModel.getEditorType())))
              .addMethod(accept)
              .addMethod(createDelegate)
              .build();

      JavaFile driverFile = JavaFile.builder(pkgName, driverType).build();

      driverFile.writeTo(writer);
    }
    //end driver
  }

  private String createNameFromEnclosedTypes(TypeElement interfaceToImplement, String suffix) {
    StringJoiner joiner = new StringJoiner("_", "", suffix);
    ClassName.get(interfaceToImplement).simpleNames().forEach(joiner::add);
    return joiner.toString();
  }
}
