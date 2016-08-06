package de.gishmo.gwt.editor.processor;

import com.google.auto.common.BasicAnnotationProcessor.ProcessingStep;
import com.google.auto.common.MoreTypes;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;
import com.google.gwt.editor.client.EditorVisitor;
import com.google.gwt.editor.client.SimpleBeanEditorDriver;
import com.google.gwt.editor.client.impl.AbstractSimpleBeanEditorDriver;
import com.google.gwt.editor.client.impl.RootEditorContext;
import com.google.gwt.editor.client.impl.SimpleBeanEditorDelegate;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import de.gishmo.gwt.editor.client.annotation.IsDriver;
import de.gishmo.gwt.editor.processor.model.EditorModel;
import de.gishmo.gwt.editor.processor.model.EditorProperty;
import de.gishmo.gwt.editor.processor.model.EditorTypes;

import javax.annotation.Generated;
import javax.annotation.processing.Filer;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
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
      EditorModel root = new EditorModel(messager, new EditorTypes(types, elements), element.asType(), types.erasure(elements.getTypeElement(getDriverInterfaceType().toString()).asType()));

      //TODO watch for messages and give up if we see errors, return that rather than empty set
      try {
        generateDriver((TypeElement)element, root);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    return ImmutableSet.of();
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
      ParameterizedTypeName delegateType = ParameterizedTypeName.get(getEditorDelegateType(),  TypeName.get(rootEditorModel.getProxyType()), TypeName.get(rootEditorModel.getEditorType()));
      MethodSpec createDelegate = MethodSpec.methodBuilder("createDelegate")
              .addModifiers(Modifier.PROTECTED)
              .returns(delegateType)
              .addAnnotation(Override.class)
              .addStatement("return new $T()", getEditorDelegate(rootEditorModel.getRootData()))//TODO
              .build();

      //implement interface, extend BaseEditorDriver or whatnot
      TypeSpec driverType = TypeSpec.classBuilder(typeName)
              .addModifiers(Modifier.PUBLIC)
              .addAnnotation(AnnotationSpec.builder(Generated.class).addMember("value", "\"$L\"", "de.gishmo.gwt.editor.processor.DriverProcessor").build())
              .addSuperinterface(TypeName.get(interfaceToImplement.asType()))
              .superclass(ParameterizedTypeName.get(getDriverSuperclassType(), TypeName.get(rootEditorModel.getProxyType()), TypeName.get(rootEditorModel.getEditorType())))
              .addMethod(accept)
              .addMethod(createDelegate)
              .build();

      JavaFile driverFile = JavaFile.builder(pkgName, driverType).build();

      driverFile.writeTo(writer);
    }
    //end driver
  }

  private ClassName getEditorDelegate(EditorProperty data) throws IOException {
    String delegateSimpleName =
            escapedMaybeParameterizedBinaryName(data.getEditorType())
            + "_"
            + getEditorDelegateType().simpleName();
    String packageName = elements.getPackageOf(types.asElement(data.getEditorType())).toString();

    try {
      JavaFileObject sourceFile = filer.createSourceFile(packageName + "." + delegateSimpleName);
      try (Writer writer = sourceFile.openWriter()) {


        FieldSpec editorField = FieldSpec.builder(ClassName.get(data.getEditorType()), "editor", Modifier.PRIVATE).build();
        FieldSpec objectField = FieldSpec.builder(ClassName.get(data.getEditedType()), "object", Modifier.PRIVATE).build();

        MethodSpec getEditorMethod = MethodSpec.methodBuilder("getEditor")
                .addModifiers(Modifier.PROTECTED)
                .returns(ClassName.get(data.getEditorType()))
                .addAnnotation(Override.class)
                .addStatement("return editor")
                .build();

        MethodSpec setEditorMethod = MethodSpec.methodBuilder("setEditor")
                .addModifiers(Modifier.PROTECTED)
                .returns(void.class)
                .addAnnotation(Override.class)
                .addParameter(ClassName.get(data.getEditorType()), "editor")
                .addStatement("this.editor = editor")
                .build();

        MethodSpec getObjectMethod = MethodSpec.methodBuilder("getObject")
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get(data.getEditedType()))
                .addAnnotation(Override.class)
                .addStatement("return object")
                .build();

        MethodSpec setObjectMethod = MethodSpec.methodBuilder("setObject")
                .addModifiers(Modifier.PROTECTED)
                .returns(void.class)
                .addAnnotation(Override.class)
                .addParameter(ClassName.get(data.getEditedType()), "object")
                .addStatement("this.object = object")
                .build();

        MethodSpec initializeSubDelegatesMethod = MethodSpec.methodBuilder("initializeSubDelegates")
                .addModifiers(Modifier.PROTECTED)
                .returns(void.class)
                .addAnnotation(Override.class)
                //TODO body
                .build();

        MethodSpec acceptMethod = MethodSpec.methodBuilder("accept")
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addAnnotation(Override.class)
                .addParameter(EditorVisitor.class, "visitor")
                //TODO body
                .build();

        TypeSpec delegateType = TypeSpec.classBuilder(delegateSimpleName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(Generated.class).addMember("value", "\"$L\"", "de.gishmo.gwt.editor.processor.DriverProcessor").build())
                .superclass(ParameterizedTypeName.get(getEditorDelegateType(), ClassName.get(data.getEditedType()), ClassName.get(data.getEditorType())))
                .addField(editorField)
                .addField(objectField)
                .addMethod(getEditorMethod)
                .addMethod(setEditorMethod)
                .addMethod(getObjectMethod)
                .addMethod(setObjectMethod)
                .addMethod(initializeSubDelegatesMethod)
                .addMethod(acceptMethod)
                .build();

        JavaFile delegateFile = JavaFile.builder(packageName, delegateType).build();

        delegateFile.writeTo(writer);
      }
    } catch (FilerException ignored) {
      //already exists, ignore
    }

    return ClassName.get(packageName, delegateSimpleName);
  }

  protected ClassName getDriverInterfaceType() {
    return ClassName.get(SimpleBeanEditorDriver.class);
  }

  protected ClassName getDriverSuperclassType() {
    return ClassName.get(AbstractSimpleBeanEditorDriver.class);
  }

  protected ClassName getEditorDelegateType() {
    return ClassName.get(SimpleBeanEditorDelegate.class);
  }

  private String escapedMaybeParameterizedBinaryName(TypeMirror editor) {
    /*
     * The parameterization of the editor type is included to ensure that a
     * correct specialization of a CompositeEditor will be generated. For
     * example, a ListEditor<Person, APersonEditor> would need a different
     * delegate from a ListEditor<Person, AnotherPersonEditor>.
     */
    StringBuilder maybeParameterizedName = new StringBuilder(
            createNameFromEnclosedTypes(MoreTypes.asTypeElement(editor), null));

    //recursive departure from gwt, in case we have ListEditor<Generic<Foo>, GenericEditor<Foo>>, etc
    for (TypeMirror typeParameterElement : MoreTypes.asDeclared(editor).getTypeArguments()) {
      maybeParameterizedName.append("$").append(escapedMaybeParameterizedBinaryName(typeParameterElement));
    }
    return escapedBinaryName(maybeParameterizedName.toString());
  }

  private String escapedBinaryName(String binaryName) {
    return binaryName.replace("_", "_1").replace('$', '_').replace('.', '_');
  }

  /**
   * Joins the name of the type with any enclosing types, with "_" as the delimeter, and appends an optional suffix.
   */
  private String createNameFromEnclosedTypes(TypeElement interfaceToImplement, String suffix) {
    StringJoiner joiner = new StringJoiner("_", "", suffix == null ? "" : suffix);
    ClassName.get(interfaceToImplement).simpleNames().forEach(joiner::add);
    return joiner.toString();
  }
}
