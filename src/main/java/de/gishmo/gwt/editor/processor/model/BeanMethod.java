package de.gishmo.gwt.editor.processor.model;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import java.beans.Introspector;

import static com.google.web.bindery.autobean.vm.impl.BeanMethod.GET_PREFIX;
import static com.google.web.bindery.autobean.vm.impl.BeanMethod.HAS_PREFIX;
import static com.google.web.bindery.autobean.vm.impl.BeanMethod.IS_PREFIX;
import static com.google.web.bindery.autobean.vm.impl.BeanMethod.SET_PREFIX;

public enum BeanMethod {
  GET {
    @Override
    public String inferName(ExecutableElement method) {
      if (isBooleanProperty(method) && method.getSimpleName().toString().startsWith(IS_PREFIX)) {
        return Introspector.decapitalize(method.getSimpleName().toString().substring(2));
      }
      return super.inferName(method);
    }

    @Override
    public boolean matches(ExecutableElement method) {
      if (method.getParameters().size() > 0) {
        return false;
      }

      if (isBooleanProperty(method)) {
        return true;
      }

      String name = method.getSimpleName().toString();
      if (name.startsWith(GET_PREFIX) && name.length() > 3) {
        return true;
      }
      return false;
    }

    /**
     * Returns {@code true} if the method matches {@code boolean isFoo()} or
     * {@code boolean hasFoo()} property accessors.
     */
    private boolean isBooleanProperty(ExecutableElement method) {
      TypeMirror returnType = method.getReturnType();
      if (returnType.getKind() == TypeKind.BOOLEAN
              || returnType.toString().equals(Boolean.class.getName())) {
        String name = method.getSimpleName().toString();
        if (name.startsWith(IS_PREFIX) && name.length() > 2) {
          return true;
        }
        if (name.startsWith(HAS_PREFIX) && name.length() > 3) {
          return true;
        }
      }
      return false;
    }
  },
  SET {
    @Override
    public boolean matches(ExecutableElement method) {
      if (method.getReturnType().getKind() != TypeKind.VOID) {
        return false;
      }
      if (method.getParameters().size() != 1) {
        return false;
      }
      String name = method.getSimpleName().toString();
      if (name.startsWith(SET_PREFIX) && name.length() > 3) {
        return true;
      }
      return false;
    }
  },
  SET_BUILDER {
    @Override
    public boolean matches(ExecutableElement method) {
      TypeMirror returnClass = method.getReturnType();
      if (returnClass == null
              || !returnClass.equals(method.getEnclosingElement())) {     //TODO HACK
        return false;
      }
      if (method.getParameters().size() != 1) {
        return false;
      }
      String name = method.getSimpleName().toString();
      if (name.startsWith(SET_PREFIX) && name.length() > 3) {
        return true;
      }
      return false;
    }
  },
  CALL {
    /**
     * Matches all leftover methods.
     */
    @Override
    public boolean matches(ExecutableElement method) {
      return true;
    }
  };

  /**
   * Determine which Action a method maps to.
   */
  public static BeanMethod which(ExecutableElement method) {
    for (BeanMethod action : BeanMethod.values()) {
      if (action.matches(method)) {
        return action;
      }
    }
    throw new RuntimeException("CALL should have matched");
  }

  /**
   * Infer the name of a property from the method.
   */
  public String inferName(ExecutableElement method) {
    if (this == CALL) {
      throw new UnsupportedOperationException(
              "Cannot infer a property name for a CALL-type method");
    }
    return Introspector.decapitalize(method.getSimpleName().toString().substring(3));
  }

  /**
   * Returns {@code true} if the BeanLikeMethod matches the method.
   */
  public abstract boolean matches(ExecutableElement method);
}