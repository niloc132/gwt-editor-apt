package de.gishmo.gwt.editor.client.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

/**
 * Marks a editor driver interface as being usable and causes a generated implementation of it to
 * be created, with required supporting classes.
 */
@Documented
@Target(ElementType.TYPE)
public @interface IsDriver {
}
