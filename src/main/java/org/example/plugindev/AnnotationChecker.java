package org.example.plugindev;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import java.util.*;

/**
 * Utility class for checking annotations in a given project.
 * This class inspects classes to ensure proper use of Spring annotations,
 * such as @Autowired, @Service, @Repository and @Component.
 */
public class AnnotationChecker {
    private static final Logger logger = Logger.getInstance(AnnotationChecker.class);

    private static final String AUTOWIRED_ANNOTATION = "org.springframework.beans.factory.annotation.Autowired";
    private static final Set<String> ANNOTATION_SET = new HashSet<>();

    static {
        ANNOTATION_SET.add("org.springframework.stereotype.Service");
        ANNOTATION_SET.add("org.springframework.stereotype.Component");
        ANNOTATION_SET.add("org.springframework.stereotype.Repository");
        ANNOTATION_SET.add("org.springframework.stereotype.Controller");
    }

    private final Set<PsiClass> visitedBeans = new HashSet<>();
    private final Set<PsiClass> initializedBeans = new HashSet<>();
    private final List<PsiClass> currentPath = new ArrayList<>();
    private final Set<String> visitedCycles = new HashSet<>();
    private final Set<String> beanClassNames = new HashSet<>();


    private void showErrorNotification(String message, Project project) {
        Notification notification = new Notification(
                "annotationCheckerGroup",
                "Annotation checker",
                message,
                NotificationType.ERROR
        );
        Notifications.Bus.notify(notification, project);
        throw new RuntimeException("Error notification shown: " + message);
    }

    /**
     * Gets the set of bean class names discovered during annotation checks.
     *
     * @return a set of fully qualified bean class names.
     */
    public Set<String> getBeanClassNames() {
        return beanClassNames;
    }

    /**
     * Checks the annotations of the specified class.
     * This method inspects fields, constructors, and methods for proper Spring annotations.
     *
     * @param psiClass the class to check
     * @param project  the current project
     */
    public void checkAnnotations(@NotNull PsiClass psiClass, @NotNull Project project) {
        addAutowiredFields(psiClass);
        addConstructorParameters(psiClass);
        addSetterMethodParameters(psiClass);

//        if (!hasServiceAnnotation(psiClass) && hasAutowiredFields(psiClass)
//                && !psiClass.isInterface() && !psiClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
//            if (!isBeanDefinedInXml(psiClass, project)) {
//                warnMissingServiceAnnotation(psiClass, project);
//            }
//        }

        if (hasServiceAnnotation(psiClass)) {
            beanClassNames.add(psiClass.getQualifiedName());
            checkConstructors(psiClass, project);
            checkConstructorParametersForServiceAnnotation(psiClass, project);
            checkFieldsForServiceAnnotation(psiClass, project);
            checkSetterParametersForServiceAnnotation(psiClass, project);

            MultipleBeansAndDuplicateQualifiers qualifierChecker = new MultipleBeansAndDuplicateQualifiers();
            qualifierChecker.checkQualifier(psiClass, project);
            beanClassNames.addAll(qualifierChecker.getQualifierNames());

            CyclicDependencyDetector cyclicDependencyDetector = new CyclicDependencyDetector(qualifierChecker);
            cyclicDependencyDetector.hasCircularDependency(psiClass, visitedBeans, initializedBeans, currentPath, visitedCycles, project);

            PayloadChecker payloadChecker = new PayloadChecker();
            payloadChecker.checkAnnotation(psiClass, project);
        }
    }

    /**
     * Adds class names of fields annotated with @Autowired to the bean class names set.
     *
     * @param psiClass the class to inspect
     */
    private void addAutowiredFields(PsiClass psiClass) {
        for (PsiField field : psiClass.getAllFields()) {
            if (field.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                PsiType fieldType = field.getType();
                if (fieldType instanceof PsiClassType) {
                    PsiClass fieldClass = ((PsiClassType) fieldType).resolve();
                    if (fieldClass != null) {
                        beanClassNames.add(fieldClass.getQualifiedName());
                    }
                }
            }
        }
    }

    /**
     * Adds class names of constructor parameters to the bean class names set.
     *
     * @param psiClass the class to inspect
     */
    private void addConstructorParameters(PsiClass psiClass) {
        PsiMethod[] constructors = psiClass.getConstructors();
        if(constructors.length==1)
        {
            PsiMethod constructor = constructors[0];
            PsiParameterList parameterList = constructor.getParameterList();

            for (PsiParameter parameter : parameterList.getParameters()) {
                PsiType parameterType = parameter.getType();
                if (parameterType instanceof PsiClassType) {
                    PsiClass parameterClass = ((PsiClassType) parameterType).resolve();
                    if (parameterClass != null) {
                        beanClassNames.add(parameterClass.getQualifiedName());
                    }
                }
            }
        }
        else {
            for (PsiMethod constructor : constructors) {
                if (constructor.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                    PsiParameterList parameterList = constructor.getParameterList();

                    for (PsiParameter parameter : parameterList.getParameters()) {
                        PsiType parameterType = parameter.getType();
                        if (parameterType instanceof PsiClassType) {
                            PsiClass parameterClass = ((PsiClassType) parameterType).resolve();
                            if (parameterClass != null) {
                                beanClassNames.add(parameterClass.getQualifiedName());
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Adds class names of setter method parameters to the bean class names set.
     *
     * @param psiClass the class to inspect
     */
    private void addSetterMethodParameters(PsiClass psiClass) {
        for (PsiMethod method : psiClass.getMethods()) {
            if (method.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                for (PsiParameter parameter : method.getParameterList().getParameters()) {
                    PsiType parameterType = parameter.getType();
                    if (parameterType instanceof PsiClassType) {
                        PsiClass parameterClass = ((PsiClassType) parameterType).resolve();
                        if (parameterClass != null) {
                            beanClassNames.add(parameterClass.getQualifiedName());
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks if a class has fields annotated with @Autowired.
     *
     * @param psiClass the class to inspect
     * @return true if the class has @Autowired fields, false otherwise
     */
    private boolean hasAutowiredFields(PsiClass psiClass) {
        for (PsiField field : psiClass.getAllFields()) {
            System.out.println("Field: " + field.getName());
            for (PsiAnnotation annotation : field.getAnnotations()) {
                System.out.println("Annotation: " + annotation.getQualifiedName());
            }
            if (field.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Logs a warning if a class with @Autowired fields is missing a service annotation.
     *
     * @param psiClass the class to inspect
     */
    void warnMissingServiceAnnotation(PsiClass psiClass, Project project) {
        String message = "Class " + psiClass.getQualifiedName() +
                " contains @Autowired fields but is not annotated with @Service or similar. Consider annotating the class with @Service.";
        showErrorNotification(message, project);
        logger.warn(message);
    }



    /**
     * Checks the parameters of all constructors in the class for proper service annotation.
     * If the class has a single constructor, checks its parameters.
     * If the class has multiple constructors with @Autowired annotation, checks their parameters.
     *
     * @param psiClass the class to inspect
     * @param project  the current project
     */
    private void checkConstructorParametersForServiceAnnotation(@NotNull PsiClass psiClass, @NotNull Project project) {
        checkSingleConstructorParameters(psiClass, project);
        checkAutowiredConstructorParameters(psiClass, project);
    }

    /**
     * Checks the parameters of the single constructor in the class for proper service annotation.
     * This method is invoked if the class has only one constructor.
     *
     * @param psiClass the class to inspect
     * @param project  the current project
     */
    private void checkSingleConstructorParameters(@NotNull PsiClass psiClass, @NotNull Project project) {
        PsiMethod[] constructors = psiClass.getConstructors();
        if (constructors.length == 1) {
            PsiMethod constructor = constructors[0];
            for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
                PsiType parameterType = parameter.getType();
                if (parameterType instanceof PsiClassType) {
                    PsiClass parameterClass = ((PsiClassType) parameterType).resolve();
                    if (parameterClass != null && !parameterClass.isInterface() && !parameterClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
                        // Check if the parameter is a built-in Java class
                        if (!Objects.requireNonNull(parameterClass.getQualifiedName()).startsWith("java.")) {
                            // Check if the parameter is annotated with @Service
                            if (!hasServiceAnnotation(parameterClass)) {
                                String message = "Parameter " + parameter.getName() + " in class " + psiClass.getQualifiedName() +
                                        " has a type " + parameterClass.getQualifiedName() +
                                        " that is not annotated with @Service. Consider annotating " +
                                        parameterClass.getName() + " with @Service.";
                                showErrorNotification(message, project);
                                logger.warn(message);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks the parameters of constructors annotated with @Autowired in the class for proper service annotation.
     * This method is invoked if the class has multiple constructors.
     *
     * @param psiClass the class to inspect
     * @param project  the current project
     */
    private void checkAutowiredConstructorParameters(@NotNull PsiClass psiClass, @NotNull Project project) {
        PsiMethod[] constructors = psiClass.getConstructors();
        for (PsiMethod constructor : constructors) {
            if (constructor.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
                    PsiType parameterType = parameter.getType();
                    if (parameterType instanceof PsiClassType) {
                        PsiClass parameterClass = ((PsiClassType) parameterType).resolve();
                        if (parameterClass != null && !parameterClass.isInterface() && !parameterClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
                            if (!Objects.requireNonNull(parameterClass.getQualifiedName()).startsWith("java.")) {
                                if (!hasServiceAnnotation(parameterClass)) {
                                    String message = "Parameter " + parameter.getName() + " in class " + psiClass.getQualifiedName() +
                                            " has a type " + parameterClass.getQualifiedName() +
                                            " that is not annotated with @Service. Consider annotating " +
                                            parameterClass.getName() + " with @Service.";
                                    showErrorNotification(message, project);
                                    logger.warn(message);
                                }
                            }
                        }
                    }
                }
            }
        }
    }



    /**
     * Checks if class of a particular field initialised are properly annotated with a service annotation.
     *
     * @param psiClass the class to inspect
     */
    private void checkFieldsForServiceAnnotation(PsiClass psiClass,Project project) {
        for (PsiField field : psiClass.getAllFields()) {
            PsiType fieldType = field.getType();
            if (fieldType instanceof PsiClassType) {
                PsiClass fieldClass = ((PsiClassType) fieldType).resolve();
                if (fieldClass != null && !fieldClass.isInterface() && !fieldClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
                    if (!Objects.requireNonNull(fieldClass.getQualifiedName()).startsWith("java.")) {
                        if (!hasServiceAnnotation(fieldClass) && (field.hasAnnotation(AUTOWIRED_ANNOTATION))) {
                            String message = "Field " + field.getName() + " in class " + psiClass.getQualifiedName() +
                                    " has a type " + fieldClass.getQualifiedName() +
                                    " that is not annotated with @Service. Consider annotating " +
                                    fieldClass.getQualifiedName() + " with @Service.";
                            showErrorNotification(message, project);
                            logger.warn(message);
                        }
                    }
                }
            }
        }
    }

    private void checkSetterParametersForServiceAnnotation(PsiClass psiClass, Project project) {
        // Iterate over all methods in the class
        for (PsiMethod method : psiClass.getMethods()) {
            // Check if the method is a setter (starts with "set" and has exactly one parameter)
            if (method.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                PsiParameter[] parameters = method.getParameterList().getParameters();
                for (PsiParameter parameter : parameters) {
                    PsiType parameterType = parameter.getType();
                    if (parameterType instanceof PsiClassType) {
                        PsiClass parameterClass = ((PsiClassType) parameterType).resolve();
                        if (parameterClass != null && !parameterClass.isInterface() && !parameterClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
                            // Check if the parameter is annotated with @Autowired
                            if (!Objects.requireNonNull(parameterClass.getQualifiedName()).startsWith("java.")) {
                                if (!hasServiceAnnotation(parameterClass)) {
                                    String message = "Parameter " + parameter.getName() + " in class " + psiClass.getQualifiedName() +
                                            " has a type " + parameter.getName() +
                                            " that is not annotated with @Service. Consider annotating " +
                                            parameterClass.getName() + " with @Service.";
                                    showErrorNotification(message, project);
                                    logger.warn(message);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Checks constructors of a class for proper annotation usage.
     *
     * @param psiClass the class to inspect
     */
    private void checkConstructors(PsiClass psiClass,Project project) {
        PsiMethod[] constructors = psiClass.getConstructors();
        if (constructors.length > 1) {
            checkMultipleConstructors(constructors, psiClass,project);
        }
    }


    /**
     * Checks multiple constructors of a class for proper annotation usage.
     *
     * @param constructors the constructors to inspect
     * @param psiClass     the class to which the constructors belong
     * @param project      the current project
     */
    private void checkMultipleConstructors(PsiMethod[] constructors, PsiClass psiClass, Project project) {
        checkForAutowiredConstructor(constructors, psiClass, project);
        checkForMultipleAutowiredConstructors(constructors, project);
    }

    /**
     * Checks if a class with multiple constructors has at least one constructor annotated with @Autowired.
     * If no constructor is annotated with @Autowired and there is no default constructor, it shows an error.
     *
     * @param constructors the constructors to inspect
     * @param psiClass     the class to which the constructors belong
     * @param project      the current project
     */
    private void checkForAutowiredConstructor(PsiMethod[] constructors, PsiClass psiClass, Project project) {
        boolean hasAutowiredConstructor = false;
        boolean hasDefaultConstructor = false;

        for (PsiMethod constructor : constructors) {
            if (constructor.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                hasAutowiredConstructor = true;
            }

            if (constructor.getParameterList().getParametersCount() == 0) {
                hasDefaultConstructor = true;
            } else {
                for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
                    PsiField field = psiClass.findFieldByName(parameter.getName(), false);
                    if (field == null || !field.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                        break;
                    }
                }
            }
        }

        if (!hasAutowiredConstructor && !hasDefaultConstructor) {
            String message = "Class " + psiClass.getQualifiedName() +
                    " has multiple constructors but none are annotated with @Autowired. At least one constructor should be annotated with @Autowired.";
            showErrorNotification(message, project);
            logger.warn(message);
        }
    }

    /**
     * Checks if a class with multiple constructors annotated with @Autowired has more than one such constructor.
     * If there are multiple constructors with @Autowired, it ensures that they have 'required = false'.
     *
     * @param constructors the constructors to inspect
     * @param project      the current project
     */
    private void checkForMultipleAutowiredConstructors(PsiMethod[] constructors, Project project) {
        int autowiredConstructorCount = 0;

        for (PsiMethod constructor : constructors) {
            if (constructor.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                autowiredConstructorCount++;
            }
        }

        if (autowiredConstructorCount > 1) {
            for (PsiMethod constructor : constructors) {
                if (constructor.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                    PsiAnnotation autowiredAnnotation = constructor.getAnnotation(AUTOWIRED_ANNOTATION);
                    assert autowiredAnnotation != null;
                    PsiAnnotationMemberValue requiredAttribute = autowiredAnnotation.findAttributeValue("required");
                    if (requiredAttribute == null || !"false".equals(requiredAttribute.getText())) {
                        String message = "Constructor " + constructor.getName() +
                                " annotated with @Autowired does not have required = false.";
                        showErrorNotification(message, project);
                        logger.warn(message);
                        return;
                    }
                }
            }
        }
    }


    /**
     * Checks if a class has a service annotation (@Service, @Component, @Repository).
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

    /**
     * Finds a class by its name within the given project.
     *
     * @param className the fully qualified name of the class
     * @param project   the current project
     * @return the found class, or null if not found
     */
    public static PsiClass findPsiClassByName(String className, Project project) {
        GlobalSearchScope scope = GlobalSearchScope.everythingScope(project);
        logger.info("Searching for class: " + className + " in scope: " + scope);
        PsiClass[] classes = JavaPsiFacade.getInstance(project).findClasses(className, scope);
        if (classes.length == 0) {
            logger.warn("No classes found with name: " + className);
        }
        return classes.length > 0 ? classes[0] : null;
    }

}