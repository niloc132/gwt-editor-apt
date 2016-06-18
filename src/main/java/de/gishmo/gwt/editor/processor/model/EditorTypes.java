package de.gishmo.gwt.editor.processor.model;

import com.google.gwt.editor.client.CompositeEditor;
import com.google.gwt.editor.client.Editor;
import com.google.gwt.editor.client.HasEditorDelegate;
import com.google.gwt.editor.client.HasEditorErrors;
import com.google.gwt.editor.client.IsEditor;
import com.google.gwt.editor.client.LeafValueEditor;
import com.google.gwt.editor.client.ValueAwareEditor;

import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Created by colin on 7/17/16.
 */
public class EditorTypes {
  private final Types types;
  private final Elements elements;

  public EditorTypes(Types types, Elements elements) {
    this.types = types;
    this.elements = elements;
  }

  public Types getTypes() {
    return types;
  }
  public Elements getElements() {
    return elements;
  }

  public TypeMirror getCompositeEditorInterface() {
    return types.erasure(elements.getTypeElement(CompositeEditor.class.getName()).asType());
  }
  public TypeMirror getIsEditorInterface() {
    return types.erasure(elements.getTypeElement(IsEditor.class.getName()).asType());
  }
  public TypeMirror getEditorInterface() {
    return types.erasure(elements.getTypeElement(Editor.class.getName()).asType());
  }
  public TypeMirror getLeafValueEditorInterface() {
    return types.erasure(elements.getTypeElement(LeafValueEditor.class.getName()).asType());
  }

  public TypeMirror getHasEditorErrorsInterface() {
    return types.erasure(elements.getTypeElement(HasEditorErrors.class.getName()).asType());
  }

  public TypeMirror getHasEditorDelegateInterface() {
    return types.erasure(elements.getTypeElement(HasEditorDelegate.class.getName()).asType());
  }

  public TypeMirror getValueAwareEditorInterface() {
    return types.erasure(elements.getTypeElement(ValueAwareEditor.class.getName()).asType());
  }
}
