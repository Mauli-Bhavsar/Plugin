package org.example.plugindev;


import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.psi.search.searches.ClassInheritorsSearch;


import com.intellij.util.Query;




import java.util.*;

/**
 * The {@code CyclicDependencyDetector} class is responsible for detecting circular dependencies
 * within a given set of PSI classes in an IntelliJ project.
 */
public class CyclicDependencyDetector {
    private static final String QUALIFIER_ANNOTATION = "org.springframework.beans.factory.annotation.Qualifier";
    private static final String AUTOWIRED_ANNOTATION = "org.springframework.beans.factory.annotation.Autowired";
    private static final String LAZY_ANNOTATION = "org.springframework.context.annotation.Lazy";

    private static final Set<String> ANNOTATION_SET = new HashSet<>();

    static {
        ANNOTATION_SET.add("org.springframework.stereotype.Service");
        ANNOTATION_SET.add("org.springframework.stereotype.Component");
        ANNOTATION_SET.add("org.springframework.stereotype.Repository");
        ANNOTATION_SET.add("org.springframework.stereotype.Controller");
    }

     private final MultipleBeansAndDuplicateQualifiers beansChecker;

    public CyclicDependencyDetector(MultipleBeansAndDuplicateQualifiers beansChecker) {
        this.beansChecker = beansChecker;
    }



    private void showErrorNotificationForCycleDetection(String message, Project project) {

        String htmlMessage = "<html><body style='width: 300px;'>" +
                "<h3 style='margin-bottom: 10px;'>Cycle Detected:</h3>" +
                "<pre>" + message + "</pre>" +
                "</body></html>";

        Notification notification = new Notification(
                "annotationCheckerGroup",
                "Cycle detection",
                htmlMessage,
                NotificationType.ERROR
        );
        Notifications.Bus.notify(notification, project);
    }

    /**
     * Checks if the specified PSI class has any circular dependencies.
     *
     * @param psiClass         the PSI class to check for circular dependencies
     * @param visitedBeans     the set of already visited beans
     * @param initializedBeans the set of already initialized beans
     * @param currentPath      the current path of beans being checked
     * @param visitedCycles    the set of already detected cycles
     * @param project          the IntelliJ project
     */
    void hasCircularDependency(PsiClass psiClass, Set<PsiClass> visitedBeans,
                               Set<PsiClass> initializedBeans, List<PsiClass> currentPath, Set<String> visitedCycles, Project project) {
        if (initializedBeans.contains(psiClass)) {
            return; // No cycle found, bean is already initialized
        }

        if (currentPath.contains(psiClass)) {
            handleCycleDetection(currentPath, psiClass, visitedCycles, project);
            return;
        }

        if (visitedBeans.contains(psiClass)) {
            return;
        }

        visitedBeans.add(psiClass);
        currentPath.add(psiClass);

        traverseFields(psiClass, visitedBeans, initializedBeans, currentPath, visitedCycles, project);
        traverseConstructors(psiClass, visitedBeans, initializedBeans, currentPath, visitedCycles, project);
        traverseSetters(psiClass, visitedBeans, initializedBeans, currentPath, visitedCycles, project);

        initializedBeans.add(psiClass);
        currentPath.remove(currentPath.size() - 1);
    }

    /**
     * Handles the detection of a circular dependency.
     *
     * @param currentPath   the current path of beans being checked
     * @param psiClass      the PSI class where the cycle is detected
     * @param visitedCycles the set of already detected cycles
     * @param project       the project plugin runs on
     */


    private void handleCycleDetection(List<PsiClass> currentPath, PsiClass psiClass, Set<String> visitedCycles, Project project) {
        String cyclePath = getCyclePath(currentPath, psiClass);
        if (!visitedCycles.contains(cyclePath)) {
            visitedCycles.add(cyclePath);
            if (currentPath.size() == 1) {
                String errorMessage = "Self loop detected:\n" + cyclePath;
                showErrorNotificationForCycleDetection(errorMessage, project);

            } else {
                System.out.println(cyclePath); // Print cycle path to console
                String errorMessage = "Cycle detected:\n" + cyclePath;
                showErrorNotificationForCycleDetection(errorMessage, project);

            }
        }
    }


    /**
     * Traverses the fields of the given PSI class to check for circular dependencies.
     *
     * @param psiClass         the PSI class to traverse
     * @param visitedBeans     the set of already visited beans
     * @param initializedBeans the set of already initialized beans
     * @param currentPath      the current path of beans being checked
     * @param visitedCycles    the set of already detected cycles
     * @param project          the IntelliJ project
     */
    private void traverseFields(PsiClass psiClass, Set<PsiClass> visitedBeans, Set<PsiClass> initializedBeans, List<PsiClass> currentPath, Set<String> visitedCycles, Project project) {
        for (PsiField field : psiClass.getAllFields()) {
            if(field.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                if (isLazy(field)) continue;
                PsiClass fieldClass = resolvePsiClassFromField(field, project,psiClass);
                if (fieldClass != null) {
                    hasCircularDependency(fieldClass, visitedBeans, initializedBeans, currentPath, visitedCycles, project);
                }
            }
        }
    }

    /**
     * Traverses the constructors of the given PSI class to check for circular dependencies.
     *
     * @param psiClass         the PSI class to traverse
     * @param visitedBeans     the set of already visited beans
     * @param initializedBeans the set of already initialized beans
     * @param currentPath      the current path of beans being checked
     * @param visitedCycles    the set of already detected cycles
     * @param project          the IntelliJ project
     */
    private void traverseConstructors(PsiClass psiClass, Set<PsiClass> visitedBeans, Set<PsiClass> initializedBeans, List<PsiClass> currentPath, Set<String> visitedCycles, Project project) {
        PsiMethod[] constructors = psiClass.getConstructors();
        if (constructors.length == 1) {
            PsiMethod constructor = constructors[0];
            for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
                if (isLazy(parameter)) continue;
                PsiClass parameterClass = resolvePsiClassFromParameter(parameter, project,psiClass);
                if (parameterClass != null) {
                    hasCircularDependency(parameterClass, visitedBeans, initializedBeans, currentPath, visitedCycles, project);
                }
            }
        } else {
            for (PsiMethod constructor : constructors) {
                if (constructor.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                    for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
                        if (isLazy(parameter)) continue;
                        PsiClass parameterClass = resolvePsiClassFromParameter(parameter, project,psiClass);
                        if (parameterClass != null) {
                            hasCircularDependency(parameterClass, visitedBeans, initializedBeans, currentPath, visitedCycles, project);
                        }
                    }
                }
            }
        }
    }
    /**
     * Traverses the setter methods of the given PSI class to check for circular dependencies.
     *
     * @param psiClass         the PSI class to traverse
     * @param visitedBeans     the set of already visited beans
     * @param initializedBeans the set of already initialized beans
     * @param currentPath      the current path of beans being checked
     * @param visitedCycles    the set of already detected cycles
     * @param project          the IntelliJ project
     */
    private void traverseSetters(PsiClass psiClass, Set<PsiClass> visitedBeans, Set<PsiClass> initializedBeans, List<PsiClass> currentPath, Set<String> visitedCycles, Project project) {
        for (PsiMethod method : psiClass.getAllMethods()) {
            if (isSetter(method) &&method.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                PsiParameter parameter = method.getParameterList().getParameters()[0];
                if (isLazy(parameter)) continue;
                PsiClass parameterClass = resolvePsiClassFromParameter(parameter, project,psiClass);
                if (parameterClass != null) {
                    hasCircularDependency(parameterClass, visitedBeans, initializedBeans, currentPath, visitedCycles, project);
                }
            }
        }
    }

    /**
     * Resolves the PSI class from the given field.
     *
     * @param field   the PSI field
     * @param project the IntelliJ project
     * @return the resolved PSI class, or {@code null} if not resolvable
     */
    private PsiClass resolvePsiClassFromField(PsiField field, Project project,PsiClass psiClass) {
        PsiType fieldType = field.getType();
        if (fieldType instanceof PsiClassType) {
            PsiClass fieldClass = ((PsiClassType) fieldType).resolve();
            if (fieldClass != null) {
                // Check if the parameter class is not an interface or abstract
                if (!fieldClass.isInterface() && !fieldClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
                    return fieldClass;
                }

                if(!field.hasAnnotation(QUALIFIER_ANNOTATION)) {
                    // Find all child classes of the parameter class
                    List<PsiClass> childClasses = findAllChildClasses(fieldClass, project);
                    if(childClasses.size()==2)
                    {
                        childClasses.remove(psiClass);
                    }

                    if (childClasses.size() == 1) {
                        return childClasses.get(0);
                    }
                }
                else {
                    return handleAbstractOrInterfaceField(field, project);
                }
            }
        }
        return null;
    }

    /**
     * Resolves the PSI class from the given parameter.
     *
     * @param parameter the PSI parameter
     * @param project   the IntelliJ project
     * @param psiClass  the current class
     * @return the resolved PSI class, or {@code null} if not resolvable
     */
    private PsiClass resolvePsiClassFromParameter(PsiParameter parameter, Project project,PsiClass psiClass) {
        PsiType parameterType = parameter.getType();

        if (parameterType instanceof PsiClassType) {
            PsiClass parameterClass = ((PsiClassType) parameterType).resolve();

            if (parameterClass != null) {
                // Check if the parameter class is not an interface or abstract
                if (!parameterClass.isInterface() && !parameterClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
                    return parameterClass;
                }

                // Find all child classes of the parameter class

                if(!parameter.hasAnnotation(QUALIFIER_ANNOTATION)) {
                    List<PsiClass> childClasses = findAllChildClasses(parameterClass, project);
                    if(childClasses.size()==2)
                    {
                        childClasses.remove(psiClass);
                    }

                    if (childClasses.size() == 1 ) {

                        return childClasses.get(0);
                    }
                }
                else {
                    return handleAbstractOrInterfaceParameter(parameter, project);
                }

            }
        }

        return null;
    }

    /**
     * Handles resolving a field's PSI class if it is an interface or abstract class.
     *
     * @param field   the PSI field
     * @param project the IntelliJ project
     * @return the resolved PSI class, or {@code null} if not resolvable
     */
    private PsiClass handleAbstractOrInterfaceField(PsiField field, Project project) {
        PsiAnnotation[] annotations = field.getAnnotations();
        for (PsiAnnotation annotation : annotations) {
            if (QUALIFIER_ANNOTATION.equals(annotation.getQualifiedName())) {
                return resolveQualifiedClass(annotation, field.getName(), project);
            }
        }
        return null;
    }

    /**
     * Finds and returns all child classes of the specified parent class within the given project.
     *
     * @param parentClass the parent class whose child classes are to be found
     * @param project     the current project context
     * @return a list of child classes that inherit from the specified parent class and are annotated with @Service
     */
    private List<PsiClass> findAllChildClasses(PsiClass parentClass, Project project) {
        List<PsiClass> childClasses = new ArrayList<>();

        if (parentClass != null) {
            GlobalSearchScope scope = GlobalSearchScope.allScope(project);
            Query<PsiClass> query = ClassInheritorsSearch.search(parentClass, scope, true);

            for (PsiClass psiClass : query) {
                if(hasServiceAnnotation(psiClass))
                    childClasses.add(psiClass);
            }
        }


        return childClasses;
    }

    /**
     * Handles resolving a parameter's PSI class if it is an interface or abstract class.
     *
     * @param parameter the PSI parameter
     * @param project   the IntelliJ project
     * @return the resolved PSI class, or {@code null} if not resolvable
     */
    private PsiClass handleAbstractOrInterfaceParameter(PsiParameter parameter, Project project) {
        PsiAnnotation[] annotations = parameter.getAnnotations();
        for (PsiAnnotation annotation : annotations) {
            if (QUALIFIER_ANNOTATION.equals(annotation.getQualifiedName())) {
                return resolveQualifiedClass(annotation, parameter.getName(), project);
            }
        }
        return null;
    }

    /**
     * Resolves the PSI class from the @Qualifier annotation.
     *
     * @param annotation  the @Qualifier annotation
     * @param elementName the name of the element
     * @param project     the IntelliJ project
     * @return the resolved PSI class, or {@code null} if not resolvable
     */
    private PsiClass resolveQualifiedClass(PsiAnnotation annotation, String elementName, Project project) {
        System.out.println("Found @Qualifier annotation on element whose type is interface or abstract class: " + elementName);
        PsiAnnotationMemberValue value = annotation.findAttributeValue("value");
        if (value != null) {
            String beanName = value.getText().replaceAll("\"", "");
            String className = capitalizeFirstLetter(beanName);
            System.out.println("Class name defined in @Qualifier: " + className);
            PsiClass resolvedClass = findClassByName(project, className);

            // Attempt to resolve using qualifier name if class name resolution failed
            if (resolvedClass == null && !beanName.isEmpty()) {
                resolvedClass = findClassByQualifierName(beanName);
            }

            if (resolvedClass != null) {
                return resolvedClass;
            } else {
                System.out.println("Could not resolve class for bean name or qualifier: " + beanName);
            }
        }
        return null;
    }

    /**
     * Finds and returns the class associated with the given qualifier name.
     *
     * @param qualifierName the name of the qualifier to look for
     * @return the class associated with the given qualifier name, or null if not found
     */
    private PsiClass findClassByQualifierName(String qualifierName) {
        Map<String, PsiClass> qualifierMap = beansChecker.getQualifierMap();
        return qualifierMap.get(qualifierName);
    }


    /**
     * Checks if the given element has a @Lazy annotation.
     *
     * @param element the PSI element
     * @return {@code true} if the element is lazy, otherwise {@code false}
     */
    private boolean isLazy(PsiModifierListOwner element) {
        PsiAnnotation lazyAnnotation = element.getAnnotation(LAZY_ANNOTATION);
        return lazyAnnotation != null;
    }

    /**
     * Capitalizes the first letter of the given string.
     *
     * @param str the string to capitalize
     * @return the string with the first letter capitalized
     */
    private String capitalizeFirstLetter(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /**
     * Generates a string representation of the detected cycle path.
     *
     * @param currentPath the current path of beans being checked
     * @param startClass  the PSI class where the cycle starts
     * @return a string representation of the cycle path
     */
    private String getCyclePath(List<PsiClass> currentPath, PsiClass startClass) {
        StringBuilder pathBuilder = new StringBuilder();
        boolean foundStart = false;
        for (int i = 0; i < currentPath.size(); i++) {
            PsiClass clazz = currentPath.get(i);
            if (clazz == startClass) {
                if (!foundStart) {
                    foundStart = true;
                    pathBuilder.append("┌─────┐\n");
                }
                pathBuilder.append("|  ").append(clazz.getQualifiedName()).append("\n");
                pathBuilder.append("↑     ↓\n");
            } else if (foundStart) {
                pathBuilder.append("|  ").append(clazz.getQualifiedName()).append("\n");
                if (i < currentPath.size() - 1) {
                    pathBuilder.append("↑     ↓\n");
                }
            }
        }
        pathBuilder.append("└─────┘");
        return pathBuilder.toString();
    }

    /**
     * Checks if the given method is a setter method.
     *
     * @param method the PSI method
     * @return {@code true} if the method is a setter, otherwise {@code false}
     */
    private boolean isSetter(PsiMethod method) {
        String methodName = method.getName();
        PsiParameterList parameterList = method.getParameterList();
        if (methodName.startsWith("set") && parameterList.getParametersCount() == 1) {
            PsiType returnType = method.getReturnType();
            return returnType != null && "void".equals(returnType.getCanonicalText());
        }
        return false;
    }

    /**
     * Finds a PSI class by its name.
     *
     * @param project   the IntelliJ project
     * @param className the name of the class to find
     * @return the PSI class, or {@code null} if not found
     */
    private static PsiClass findClassByName(Project project, String className) {
        PsiClass psiClass = null;
        PsiClass[] classes = PsiShortNamesCache.getInstance(project).getClassesByName(className, GlobalSearchScope.allScope(project));
        if (classes.length > 0) {
            psiClass = classes[0];
        }
        return psiClass;
    }

    /**
     * Checks if a class has a service annotation.
     *
     * @param psiClass the class to inspect
     * @return true if the class has a service annotation, false otherwise
     */
    private static boolean hasServiceAnnotation(PsiClass psiClass) {
        PsiModifierList modifierList = psiClass.getModifierList();
        if (modifierList != null) {
            for (String annotation : ANNOTATION_SET) {
                if (modifierList.findAnnotation(annotation) != null) {
                    return true;
                }
            }
        }
        return false;
    }
}