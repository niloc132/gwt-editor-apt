package de.gishmo.gwt.editor.processor.model;

import com.google.auto.common.MoreTypes;
import com.google.common.collect.Sets;
import com.google.gwt.editor.client.Editor;
import de.gishmo.gwt.editor.processor.ModelUtils;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * old EditorData and EditorAccess
 */
public class EditorProperty {

  public static class Builder {
    private static final String EDITOR_SUFFIX = "Editor";
    private final EditorTypes types;

    private final TypeMirror dataType;
    private TypeMirror editedType;

    private Stream.Builder<EditorProperty> builder = Stream.builder();
    private Stream<EditorProperty> stream;

    public Builder(EditorTypes types, TypeMirror dataType) {
      this.types = types;
      this.dataType = dataType;
    }

    public Builder root(TypeMirror type) {
      return access("", "", type);
    }

    public Builder access(VariableElement field) {
      String path = getPath(field.getSimpleName().toString(), field.getAnnotation(Editor.Path.class));

      return access(path, field.getSimpleName().toString(), field.asType());
    }

    public Builder access(ExecutableElement method) {
      assert method.getParameters().isEmpty();
      String path = getPath(method.getSimpleName().toString(), method.getAnnotation(Editor.Path.class));

      return access(path, method.getSimpleName().toString() + "()", method.getReturnType());
    }

    private String getPath(String memberName, Editor.Path pathAnnotation) {
      if (pathAnnotation != null) {
        return pathAnnotation.value();
      } else if (memberName.endsWith(EDITOR_SUFFIX)) {
        return memberName.substring(0, memberName.length() - EDITOR_SUFFIX.length());
      } else {
        return memberName;
      }
    }

    private Builder access(String path, String editorExpression, TypeMirror editorType) {


      TypeMirror editedType = null;
      //build zero, one, or two properties
      if (types.getTypes().isAssignable(editorType, types.getIsEditorInterface())) {
        EditorProperty property = new EditorProperty();
        property.editorType = ModelUtils.findParameterizationOf(types.getTypes(), types.getIsEditorInterface(), editorType).get(0);
        property.editedType = editedType = ModelUtils.findParameterizationOf(types.getTypes(), types.getEditorInterface(), property.editorType).get(0);
        property.simpleExpression = editorExpression + ".asEditor()";
        builder.accept(property);
      }
      if (types.getTypes().isAssignable(editorType, types.getEditorInterface())) {
        EditorProperty property = new EditorProperty();
        property.editorType = editorType;
        property.editedType = editedType = ModelUtils.findParameterizationOf(types.getTypes(), types.getEditorInterface(), editorType).get(0);
        property.simpleExpression = editorExpression;
        builder.accept(property);
      }

      stream = builder.build().peek(property -> {
        assert property.declaredPath == null;

        property.declaredPath = path;

        property.isLeaf = types.getTypes().isAssignable(property.editorType, types.getLeafValueEditorInterface());
        property.isCompositeEditor = types.getTypes().isAssignable(property.editorType, types.getCompositeEditorInterface());
        property.isDelegateRequired = types.getTypes().isAssignable(property.editorType, types.getHasEditorDelegateInterface())
                || types.getTypes().isAssignable(property.editorType, types.getHasEditorErrorsInterface())
                || !ModelUtils.isValueType(property.editedType);

        property.isValueAware = types.getTypes().isAssignable(property.editorType, types.getValueAwareEditorInterface());
      });
      builder = null;

      if (editedType != null) {
        // at least one type was added to the stream
        findBeanPropertyMethods(path, editedType);
      }


      return this;
    }

    /**
     * Traverses a path to create expressions to access the getter and setter.
     */
    private void findBeanPropertyMethods(String path, TypeMirror propertyType) {
      StringBuilder interstitialGetters = new StringBuilder();
      StringBuilder interstitialGuard = new StringBuilder("true");
      String[] parts = path.split(Pattern.quote("."));
      String setterName = null;

      TypeMirror lookingAt = dataType;
      part : for (int i = 0, j = parts.length; i < j; i++) {
        if (parts[i].length() == 0) {
          continue;
        }
        boolean lastPart = i == j - 1;
        boolean foundGetterForPart = false;

        for (ExecutableElement maybeSetter : ElementFilter.methodsIn(Sets.newLinkedHashSet(MoreTypes.asElement(lookingAt).getEnclosedElements()))) {
          BeanMethod which = BeanMethod.which(maybeSetter);
          if (BeanMethod.CALL.equals(which)) {
            continue;
          }
          if (!which.inferName(maybeSetter).equals(parts[i])) {
            continue;
          }
          switch (which) {
            case GET: {
              lookingAt = maybeSetter.getReturnType();
              if (!lastPart && lookingAt.getKind().isPrimitive()) {
//              poison(foundPrimitiveMessage(returnType, interstitialGetters.toString(), path));
                return;
              }
              interstitialGetters.append(".").append(maybeSetter.getSimpleName().toString()).append("()");
              interstitialGuard.append(" && %1$s").append(interstitialGetters).append(" != null");
              propertyOwnerType(maybeSetter.getEnclosingElement().asType());
              foundGetterForPart = true;
              if (!lastPart) {
                continue part;
              }
              break;
            }
            case SET:
            case SET_BUILDER: {
              if (lastPart && setterName == null) {
              /*
               * If looking at the last element of the path, also look for a
               * setter.
               */

                TypeMirror setterParamType = maybeSetter.getParameters().get(0).asType();
                // Handle the case of setFoo(int) vs. Editor<Integer>
                if (setterParamType.getKind().isPrimitive()) {
                  // Replace the int with Integer

                  setterParamType = types.getTypes().boxedClass((PrimitiveType) setterParamType).asType();
                }
                boolean matches = types.getTypes().isAssignable(propertyType, setterParamType);
                if (matches) {
                  setterName = maybeSetter.getSimpleName().toString();
                }
              }
              break;
            }
          }
        }
        if (!foundGetterForPart) {
//        poison(noGetterMessage(path, proxyType));
          return;
        }
      }

      int idx = interstitialGetters.lastIndexOf(".");
      beanOwnerExpression(idx <= 0 ? "" : interstitialGetters.substring(0, idx));
      if (parts.length > 1) {
        // Strip after last && since null is a valid value
        interstitialGuard.delete(interstitialGuard.lastIndexOf(" &&"), interstitialGuard.length());
        beanOwnerGuard(interstitialGuard.substring(8));
      }
      if (interstitialGetters.length() > 0) {
        getterExpression("." + interstitialGetters.substring(idx + 1, interstitialGetters.length() - 2) + "()");
      } else {
        getterExpression("");
      }
      setterName(setterName);
    }

    private Builder peek(Consumer<EditorProperty> consumer) {
      stream = stream.peek(consumer);
      return this;
    }

    public Builder getterExpression(String value) {
      return peek(property -> property.getterExpression = value);
    }

    public Builder propertyOwnerType(TypeMirror ownerType) {
      return peek(property -> property.propertyOwnerType = ownerType);
    }

    public Builder setterName(String value) {
      return peek(property -> property.setterName = value);
    }

    public Builder beanOwnerExpression(String value) {
      return peek(property -> property.beanOwnerExpression = value);
    }

    public Builder beanOwnerGuard(String value) {
      return peek(property -> property.beanOwnerGuard = value);
    }

    public List<EditorProperty> build(Optional<EditorProperty> parent) {
      if (stream == null) {
        throw new IllegalStateException();
      }
      try {
        stream = stream.peek(property -> {
          property.editorExpression = parent.map(p -> p.getExpression() + ".").orElse("") + property.simpleExpression;
          property.path = parent.map(p -> p.getPath() + ".").orElse("") + property.declaredPath;

          if (property.isCompositeEditor) {
            assert types.getTypes().isAssignable(property.editorType, types.getCompositeEditorInterface());
            TypeMirror subEditorType = ModelUtils.findParameterizationOf(types.getTypes(), types.getCompositeEditorInterface(), property.editorType).get(2);
            property.composedData = new Builder(types, dataType).root(subEditorType).build(Optional.of(property)).get(0);
          }

        });
        return stream.collect(Collectors.toList());
      } finally {
        stream = null;
      }
    }
  }

  private String beanOwnerExpression = "";
  private String beanOwnerGuard = "true";
  private EditorProperty composedData;
  private String declaredPath;
  private TypeMirror editedType;
  private TypeMirror editorType;
  private String editorExpression;
  private String getterExpression;
  private boolean isLeaf;
  private boolean isCompositeEditor;
  private boolean isDelegateRequired;
  private boolean isValueAware;
  private String path;
  private TypeMirror propertyOwnerType;
  private String setterName;
  private String simpleExpression;


  /**
   * Returns a complete expression to retrieve the editor.
   */
  public String getExpression() {
    return editorExpression;
  }

  /**
   * Returns the complete path of the editor, relative to the root object.
   */
  public String getPath() {
    return path;
  }


  public boolean isCompositeEditor() {
    return isCompositeEditor;
  }

  public EditorProperty getComposedData() {
    return composedData;
  }

  public TypeMirror getEditedType() {
    return editedType;
  }

  public TypeMirror getEditorType() {
    return editorType;
  }

  public boolean isDelegateRequired() {
    return isDelegateRequired;
  }

  public boolean isLeafValueEditor() {
    return isLeaf;
  }

  public boolean isValueAwareEditor() {
    return isValueAware;
  }


  public boolean isDeclaredPathNested() {
    return declaredPath.contains(".");
  }


  public String getBeanOwnerExpression() {
    return beanOwnerExpression;
  }


  public String getBeanOwnerGuard(String ownerExpression) {
    return String.format(beanOwnerGuard, ownerExpression);
  }

  public String getGetterExpression() {
    return getterExpression;
  }

  public String getSetterName() {
    return setterName;
  }

  public String getPropertyName() {
    return getPath().substring(getPath().lastIndexOf('.') + 1);
  }

  public TypeMirror getPropertyOwnerType() {
    return propertyOwnerType;
  }

  public String getSimpleExpression() {
    return simpleExpression;
  }

  /**
   * Gets the path specified by the {@code @Path} annotation or inferred via
   * convention.
   */
  public String getDeclaredPath() {
    return declaredPath;
  }
}