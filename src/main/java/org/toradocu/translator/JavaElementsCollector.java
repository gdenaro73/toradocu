package org.toradocu.translator;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.toradocu.extractor.DocumentedMethod;
import org.toradocu.util.Reflection;

/**
 * Collects all the Java elements that can be used for the condition translation. Java elements are
 * collected through Java reflection.
 */
public class JavaElementsCollector {

  private static URLClassLoader classLoader;
  private static final Logger log = LoggerFactory.getLogger(Matcher.class);

  public static Set<CodeElement<?>> collect(DocumentedMethod documentedMethod) {
    Set<CodeElement<?>> collectedElements = new LinkedHashSet<>();
    Class<?> containingClass =
        Reflection.getClass(documentedMethod.getContainingClass().getQualifiedName());
    List<Type> inScopeTypes = new ArrayList<>();
    inScopeTypes.add(containingClass);

    // Add the containing class as a code element.
    collectedElements.add(new ClassCodeElement(containingClass));

    // Add parameters of the documented method.
    final Executable executable = documentedMethod.getExecutable();
    int paramIndex = 0;
    for (java.lang.reflect.Parameter par : executable.getParameters()) {
      collectedElements.add(
          new ParameterCodeElement(
              par, documentedMethod.getParameters().get(paramIndex).getName(), paramIndex));
      inScopeTypes.add(par.getType());
      paramIndex++;
    }

    // Add methods of the target class.
    final List<Method> methods =
        Arrays.stream(containingClass.getMethods())
            .filter(m -> checkCompatibility(m, inScopeTypes))
            .collect(Collectors.toList());
    for (Method method : methods) {
      if (Modifier.isStatic(method.getModifiers())) {
        collectedElements.add(new StaticMethodCodeElement(method));
      } else if (!documentedMethod.isConstructor()) {
        collectedElements.add(new MethodCodeElement("target", method));
      }
    }

    return collectedElements;
  }

  private static boolean checkCompatibility(Method m, List<Type> inScopeTypes) {
    for (java.lang.reflect.Parameter parameter : m.getParameters()) {
      if (!inScopeTypes.contains(parameter.getType())) {
        return false;
      }
    }
    return true;
  }
}
