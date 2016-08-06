package de.gishmo.gwt.editor.processor;

import com.google.auto.common.BasicAnnotationProcessor.ProcessingStep;
import com.google.auto.common.MoreTypes;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.SetMultimap;
import com.google.gwt.editor.client.Editor;
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
import java.util.IdentityHashMap;
import java.util.Map;
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
              .addStatement("return new $T()", getEditorDelegate(rootEditorModel, rootEditorModel.getRootData()))
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

  private ClassName getEditorDelegate(EditorModel editorModel, EditorProperty data) throws IOException {
    String delegateSimpleName =
            escapedMaybeParameterizedBinaryName(data.getEditorType())
                    + "_"
                    + getEditorDelegateType().simpleName();
    String packageName = elements.getPackageOf(types.asElement(data.getEditorType())).toString();

    try {
      JavaFileObject sourceFile = filer.createSourceFile(packageName + "." + delegateSimpleName);
      try (Writer writer = sourceFile.openWriter()) {

        // create a raw editor type so that we can reference it throughout. Since some type
        // param of the editor might be protected or package protected, we have to make a
        // raw reference to it consistently in this generated class.
        TypeName rawEditorType = ClassName.get(types.erasure(data.getEditorType()));

        TypeSpec.Builder delegateTypeBuilder = TypeSpec.classBuilder(delegateSimpleName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(AnnotationSpec.builder(Generated.class).addMember("value", "\"$L\"", "de.gishmo.gwt.editor.processor.DriverProcessor").build())
                .superclass(getEditorDelegateType());//raw type here, for the same reason as above

        NameFactory names = new NameFactory();
        Map<EditorProperty, String> delegateFields = new IdentityHashMap<>();


        delegateTypeBuilder.addField(FieldSpec.builder(rawEditorType, "editor", Modifier.PRIVATE).build());
        names.addName("editor");
        delegateTypeBuilder.addField(FieldSpec.builder(ClassName.get(data.getEditedType()), "object", Modifier.PRIVATE).build());
        names.addName("object");

        // Fields for the sub-delegates that must be managed
        for (EditorProperty d : editorModel.getEditorData(data.getEditorType())) {
          if (d.isDelegateRequired()) {
            String fieldName = names.createName(d.getPropertyName() + "Delegate");
            delegateFields.put(d, fieldName);
            delegateTypeBuilder.addField(getEditorDelegateType(), fieldName, Modifier.PRIVATE);//TODO parameterize
          }
        }

        delegateTypeBuilder.addMethod(MethodSpec.methodBuilder("getEditor")
                .addModifiers(Modifier.PROTECTED)
                .returns(rawEditorType)
                .addAnnotation(Override.class)
                .addStatement("return editor")
                .build());

        delegateTypeBuilder.addMethod(MethodSpec.methodBuilder("setEditor")
                .addModifiers(Modifier.PROTECTED)
                .returns(void.class)
                .addAnnotation(Override.class)
                .addParameter(Editor.class, "editor")
                .addStatement("this.editor = ($T) editor", rawEditorType)
                .build());

        delegateTypeBuilder.addMethod(MethodSpec.methodBuilder("getObject")
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get(data.getEditedType()))
                .addAnnotation(Override.class)
                .addStatement("return object")
                .build());

        delegateTypeBuilder.addMethod(MethodSpec.methodBuilder("setObject")
                .addModifiers(Modifier.PROTECTED)
                .returns(void.class)
                .addAnnotation(Override.class)
                .addParameter(ClassName.get(Object.class), "object")
                .addStatement("this.object = ($T) object", ClassName.get(data.getEditedType()))
                .build());

        MethodSpec.Builder initializeSubDelegatesBuilder = MethodSpec.methodBuilder("initializeSubDelegates")
                .addModifiers(Modifier.PROTECTED)
                .returns(void.class)
                .addAnnotation(Override.class);
        if (data.isCompositeEditor()) {
          initializeSubDelegatesBuilder.addStatement("createChain($L.class)", MoreTypes.asElement(data.getComposedData().getEditedType()));
        }
        for (EditorProperty d : editorModel.getEditorData(data.getEditorType())) {
          ClassName subDelegateType = getEditorDelegate(editorModel, d);
          if (d.isDelegateRequired()) {
            initializeSubDelegatesBuilder
                    .beginControlFlow("if (editor.$L != null)", d.getSimpleExpression())
                    .addStatement("$L = new $T()", delegateFields.get(d), subDelegateType)
                    .addStatement("addSubDelegate($L, appendPath(\"$L\"), editor.$L)",
                            delegateFields.get(d),
                            d.getDeclaredPath(),
                            d.getSimpleExpression()
                    )
                    .endControlFlow();
          }
        }
        delegateTypeBuilder.addMethod(initializeSubDelegatesBuilder.build());

        MethodSpec.Builder acceptBuilder = MethodSpec.methodBuilder("accept")
                .addModifiers(Modifier.PUBLIC)
                .returns(void.class)
                .addAnnotation(Override.class)
                .addParameter(EditorVisitor.class, "visitor");
        if (data.isCompositeEditor()) {
          acceptBuilder.addStatement("getEditorChain().accept(visitor)");
        }
        for (EditorProperty d : editorModel.getEditorData(data.getEditorType())) {
          if (d.isDelegateRequired()) {
            acceptBuilder.beginControlFlow("if ($L != null)", delegateFields.get(d));
          }
//          ClassName editorContextName = getEditorContext(editorModel, data, d);
//          acceptBuilder.addStatement("$T ctx = new $T(getObject(), editor.$L, appendPath(\"$L\"))",
//                  editorContextName,
//                  editorContextName,
//                  d.getSimpleExpression(),
//                  d.getDeclaredPath()
//          );
          if (d.isDelegateRequired()) {
//            acceptBuilder.addStatement("ctx.setEditorDelegate($L)", delegateFields.get(d));
//            acceptBuilder.addStatement("ctx.traverse(visitor, $L)", delegateFields.get(d));
            acceptBuilder.endControlFlow();
          } else {
//            acceptBuilder.addStatement("ctx.traverse(visitor, null)");
          }
        }


        delegateTypeBuilder.addMethod(acceptBuilder.build());


        if (data.isCompositeEditor()) {
          ClassName compositeEditorDelegateType = getEditorDelegate(editorModel, data.getComposedData());
          delegateTypeBuilder.addMethod(MethodSpec.methodBuilder("createComposedDelegate")
                  .addModifiers(Modifier.PROTECTED)
                  .returns(compositeEditorDelegateType)
                  .addAnnotation(Override.class)
                  .addStatement("return new $T()", compositeEditorDelegateType)
                  .build());
        }
        TypeSpec delegateType = delegateTypeBuilder
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
