package de.gishmo.gwt.editor.processor.model;

import com.google.auto.common.MoreTypes;
import com.google.common.collect.Sets;
import com.google.gwt.editor.client.Editor;
import de.gishmo.gwt.editor.processor.ModelUtils;

import javax.annotation.processing.Messager;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public class EditorModel {

  //general lookup tools
  private final Messager messager;
  private final EditorTypes editorTypes;

  //details about this editor
  private final TypeMirror dataType;//"proxyType" in gwt
  private final TypeMirror editorType;

  private final EditorModel parentModel;
  private final EditorProperty editorSoFar;//???

  private final List<EditorProperty> childrenModels;

  //"Type-specific data."
  private final Map<TypeMirror, List<EditorProperty>> typeData;


  public EditorModel(Messager messager, EditorTypes editorTypes, TypeMirror intfType, TypeMirror driverType) {
    this.messager = messager;
    this.editorTypes = editorTypes;

    //assume *Driver is generic on Data, EditorOfData
    List<? extends TypeMirror> params = ModelUtils.findParameterizationOf(editorTypes.getTypes(), driverType, intfType);
    assert params != null : "Can't parameterize " + intfType + " as a driver";
    dataType = params.get(0);
    editorType = params.get(1);

    parentModel = null;
    editorSoFar = null;
    typeData = new HashMap<>();

    childrenModels = calculateEditorData();
  }

  public EditorModel(Messager messager, EditorTypes editorTypes, EditorModel parent, TypeMirror editorType, EditorProperty subEditor, TypeMirror proxyType) {
    this.messager = messager;
    this.editorTypes = editorTypes;

    this.dataType = proxyType;
    this.editorType = editorType;
    editorSoFar = subEditor;

    this.parentModel = parent;
    this.typeData = parent.typeData;

    childrenModels = calculateEditorData();
  }

  private List<EditorProperty> calculateEditorData() {
    List<EditorProperty> flatData = new ArrayList<>();
    List<EditorProperty> toReturn = new ArrayList<>();

    // Only look for sub-editor accessors if the editor isn't a leaf
    if (!editorTypes.getTypes().isAssignable(editorType, editorTypes.getLeafValueEditorInterface())) {
      Set<VariableElement> fields = ElementFilter.fieldsIn(Sets.newLinkedHashSet(MoreTypes.asElement(editorType).getEnclosedElements()));
      for (VariableElement field : fields) {
        if (field.getModifiers().contains(Modifier.PRIVATE)
                || field.getModifiers().contains(Modifier.STATIC)
                || field.getAnnotation(Editor.Ignore.class) != null) {
          continue;
        }
        TypeMirror fieldClassType = field.asType();
        if (shouldExamine(fieldClassType)) {
          List<EditorProperty> data = new EditorProperty.Builder(editorTypes, dataType)
                  .access(field)
                  .build(Optional.ofNullable(editorSoFar));
          accumulateEditorData(data, flatData, toReturn);
        }
      }
      Set<ExecutableElement> methods = ElementFilter.methodsIn(Sets.newLinkedHashSet(MoreTypes.asElement(editorType).getEnclosedElements()));
      for (ExecutableElement method : methods) {
        if (method.getModifiers().contains(Modifier.PRIVATE)
                || method.getModifiers().contains(Modifier.STATIC)
                || method.getAnnotation(Editor.Ignore.class) != null) {
          continue;
        }
        TypeMirror methodReturnType = method.getReturnType();
        if (shouldExamine(methodReturnType) && method.getParameters().size() == 0) {

          if (method.getSimpleName().toString().equals("asEditor") && editorTypes.getTypes().isAssignable(editorType, editorTypes.getIsEditorInterface())) {
            //ignore IsEditor.asEditor
            continue;
          }

          if (method.getSimpleName().toString().equals("createEditorForTraversal") && editorTypes.getTypes().isAssignable(editorType, editorTypes.getCompositeEditorInterface())) {
            //ignore CompositeEditor.createEditorForTraversal
            continue;
          }

          List<EditorProperty> data = new EditorProperty.Builder(editorTypes, dataType)
                  .access(method)
                  .build(Optional.ofNullable(editorSoFar));
          accumulateEditorData(data, flatData, toReturn);
        }
      }
    }

    if (editorTypes.getTypes().isAssignable(editorType, editorTypes.getCompositeEditorInterface())) {
      TypeMirror subEditorType = ModelUtils.findParameterizationOf(editorTypes.getTypes(), editorTypes.getCompositeEditorInterface(), editorType).get(2);

      EditorProperty subEditor = new EditorProperty.Builder(editorTypes, dataType)
              .root(subEditorType)
              .build(Optional.ofNullable(editorSoFar))
              .get(0);//doesn't matter which instance, there must be at least one
      List<EditorProperty> accumulator = new ArrayList<>();
      descendIntoSubEditor(accumulator, subEditor);

      /*
       * It's necessary to generate a sub-Model here so that any Editor types
       * reachable only through the composite type will be added to the types
       * map. The path data isn't actually useful, since we rely on
       * CompositeEditor.getPathElement() at runtime.
       */
      EditorModel subModel = new EditorModel(messager, editorTypes, this, subEditor.getEditorType(),
              subEditor, subEditor.getEditedType());
//      poisoned |= subModel.poisoned;
    }

    if (!typeData.containsKey(editorType)) {
      typeData.put(editorType, flatData);
    }

    return toReturn;
  }

  private List<EditorProperty> makeProperties(Supplier<EditorProperty.Builder> builder, TypeMirror editorType) {
    List<EditorProperty> result = new ArrayList<>();

    return null;
  }

  private boolean shouldExamine(TypeMirror fieldClassType) {
    return editorTypes.getTypes().isAssignable(fieldClassType, editorTypes.getEditorInterface())
            || editorTypes.getTypes().isAssignable(fieldClassType, editorTypes.getIsEditorInterface());
  }

  private void accumulateEditorData(List<EditorProperty> data,
                                    List<EditorProperty> flatData, List<EditorProperty> allData) {
    assert !data.isEmpty();
    flatData.addAll(data);
    allData.addAll(data);
    for (EditorProperty d : data) {
      descendIntoSubEditor(allData, d);
    }
  }

  private void descendIntoSubEditor(List<EditorProperty> accumulator, EditorProperty data) {
    EditorModel superModel = parentModel;
    while (superModel != null) {
      if (editorTypes.getTypes().isAssignable(data.getEditorType(), superModel.editorType)
              || editorTypes.getTypes().isAssignable(superModel.editorType, data.getEditorType())) {
//        poison(cycleErrorMessage(data.getEditorType(), superModel.getPath(),
//                data.getPath()));
        return;
      }
      superModel = superModel.parentModel;
    }

    if (data.isDelegateRequired()) {
      EditorModel subModel = new EditorModel(messager, editorTypes, this, data.getEditorType(), data,
              ModelUtils.findParameterizationOf(editorTypes.getTypes(), editorTypes.getEditorInterface(), data.getEditorType()).get(0));
      accumulator.addAll(accumulator.indexOf(data) + 1,
              new ArrayList<>(subModel.getEditorData()));
//      poisoned |= subModel.poisoned;
    }
  }

  public TypeMirror getEditorType() {
    return editorType;
  }

  public TypeMirror getProxyType() {
    return dataType;
  }

  public List<EditorProperty> getEditorData() {
    return childrenModels;
  }

  public List<EditorProperty> getEditorData(TypeMirror editor) {
    List<EditorProperty> toReturn = typeData.get(editor);
    if (toReturn == null) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(toReturn);
  }

  public EditorProperty getRootData() {
    return new EditorProperty.Builder(editorTypes, dataType)
            .root(getEditorType())
            .build(Optional.empty())
            .get(0);
  }
}