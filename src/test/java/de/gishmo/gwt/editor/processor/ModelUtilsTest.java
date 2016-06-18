package de.gishmo.gwt.editor.processor;

import com.google.auto.common.MoreTypes;
import com.google.testing.compile.CompilationRule;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Created by colin on 7/17/16.
 */
public class ModelUtilsTest {

  @Rule
  public CompilationRule compilationRule = new CompilationRule();

  private Elements elements;
  private Types types;

  @Before
  public void setUp() {
    this.elements = compilationRule.getElements();
    types = compilationRule.getTypes();
  }

  @Test
  public void testFindParameterizationOf() throws Exception {
    List<? extends TypeMirror> params = ModelUtils.findParameterizationOf(types, typeElementFor(A.class).asType(), typeElementFor(ConcreteA.class).asType());
    assertNotNull(params);
    Assert.assertEquals(1, params.size());
    assertEquals(typeElementFor(String.class).asType(), params.get(0));

    params = ModelUtils.findParameterizationOf(types, typeElementFor(A.class).asType(), typeElementFor(ConcreteTighten.class).asType());
    assertNotNull(params);
    Assert.assertEquals(1, params.size());
    assertEquals(typeElementFor(StringSet.class).asType(), params.get(0));

    params = ModelUtils.findParameterizationOf(types, typeElementFor(Tighten.class).asType(), typeElementFor(ConcreteTighten.class).asType());
    assertNotNull(params);
    Assert.assertEquals(1, params.size());
    assertEquals(typeElementFor(StringSet.class).asType(), params.get(0));

    params = ModelUtils.findParameterizationOf(types, typeElementFor(A.class).asType(), typeElementFor(ConcreteMultiple.class).asType());
    assertNotNull(params);
    Assert.assertEquals(1, params.size());
    assertEquals(typeElementFor(Number.class).asType(), params.get(0));

    params = ModelUtils.findParameterizationOf(types, typeElementFor(Multiple.class).asType(), typeElementFor(ConcreteMultiple.class).asType());
    assertNotNull(params);
    Assert.assertEquals(2, params.size());
    assertEquals(typeElementFor(String.class).asType(), params.get(0));
    assertEquals(typeElementFor(Number.class).asType(), params.get(1));

    params = ModelUtils.findParameterizationOf(types, typeElementFor(A.class).asType(), types.getDeclaredType(typeElementFor(Tighten.class), typeElementFor(StringSet.class).asType()));
    assertNotNull(params);
    Assert.assertEquals(1, params.size());
    assertEquals(typeElementFor(StringSet.class).asType(), params.get(0));

    //TODO Multiple to Multiple
    //TODO A to Multiple
    //TODO impossible cases like String to Number (null), or Object to String (empty list)
  }

  private static void assertEquals(TypeMirror expected, TypeMirror actual) {
    assertTrue("Expected " + expected + ", actual " + actual, MoreTypes.equivalence().equivalent(expected, actual));
  }

  interface A<T> {}

  interface ConcreteA extends A<String> {}

  interface Tighten<B extends Collection<String>> extends A<B> {}

  interface StringSet extends Set<String> {}

  interface ConcreteTighten extends Tighten<StringSet> {}

  interface Multiple<B,T> extends A<T>{}

  interface ConcreteMultiple extends Multiple<String, Number> {}



  private TypeElement typeElementFor(Class<?> clazz) {
    return elements.getTypeElement(clazz.getCanonicalName());
  }
}