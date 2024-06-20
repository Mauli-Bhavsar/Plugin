package org.example.plugindev;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.indexing.FileBasedIndex;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**s
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

        if (!hasServiceAnnotation(psiClass) && hasAutowiredFields(psiClass)) {
            warnMissingServiceAnnotation(psiClass,project);
        }

        if (hasServiceAnnotation(psiClass)) {
            beanClassNames.add(psiClass.getQualifiedName());
            checkConstructorParametersForServiceAnnotation(psiClass,project);
            checkFieldsForServiceAnnotation(psiClass,project);
            checkSetterParametersForServiceAnnotation(psiClass,project);
            checkConstructors(psiClass,project);
            checkFieldsForAutowired(psiClass,project);

            MultipleBeansAndDuplicateQualifiers qualifierChecker = new MultipleBeansAndDuplicateQualifiers();
            qualifierChecker.checkQualifier(psiClass, project);
            beanClassNames.addAll(qualifierChecker.getQualifierNames());

            CyclicDependencyDetector cyclicDependencyDetector = new CyclicDependencyDetector(qualifierChecker);
            cyclicDependencyDetector.hasCircularDependency(psiClass, visitedBeans, initializedBeans, currentPath, visitedCycles, project);

            PayloadChecker payloadChecker=new PayloadChecker();
            payloadChecker.checkAnnotation(psiClass,project);
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
        for (PsiMethod constructor : psiClass.getConstructors()) {
            for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
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

    /**
     * Adds class names of setter method parameters to the bean class names set.
     *
     * @param psiClass the class to inspect
     */
    private void addSetterMethodParameters(PsiClass psiClass) {
        for (PsiMethod method : psiClass.getMethods()) {
            if (isSetter(method)) {
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

    private void checkSetterParametersForServiceAnnotation(PsiClass psiClass, Project project) {
        // Iterate over all methods in the class
        for (PsiMethod method : psiClass.getMethods()) {
            // Check if the method is a setter (starts with "set" and has exactly one parameter)
            if (isSetter(method) && method.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                // Retrieve the parameter of the setter method
                PsiParameter[] parameters = method.getParameterList().getParameters();
                if (parameters.length == 1) {
                    PsiParameter parameter = parameters[0];
                    PsiType parameterType = parameter.getType();
                    if (parameterType instanceof PsiClassType) {
                        PsiClass parameterClass = ((PsiClassType) parameterType).resolve();
                        if (parameterClass != null && !parameterClass.isInterface() && !parameterClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
                            // Check if the parameter is annotated with @Autowired
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

    /**
     * Checks constructors of a class for proper annotation usage.
     *
     * @param psiClass the class to inspect
     */
    private void checkConstructors(PsiClass psiClass,Project project) {
        PsiMethod[] constructors = psiClass.getConstructors();
        if (constructors.length == 1) {
            checkSingleConstructor(constructors[0], psiClass,project);
        } else if (constructors.length > 1) {
            checkMultipleConstructors(constructors, psiClass,project);
        }
    }

    /**
     * Checks a single constructor of a class for proper annotation usage.
     *
     * @param constructor the constructor to inspect
     * @param psiClass    the class to which the constructor belongs
     */
    private void checkSingleConstructor(PsiMethod constructor, PsiClass psiClass,Project project) {
        if (!constructor.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                String message = "Single constructor in class " + psiClass.getQualifiedName() +
                        " is not annotated with @Autowired. Using @Autowired is recommended.";
                showErrorNotification(message, project);
                logger.warn(message);

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

                    }
                }
            }
        }
    }


    /**
     * Checks fields of a class for proper @Autowired annotation usage.
     *
     * @param psiClass the class to inspect
     */
    private void checkFieldsForAutowired(PsiClass psiClass,Project project) {
        Set<String> fieldNamesInConstructors = collectFieldNamesInConstructors(psiClass);
        Set<String> fieldNamesInSetters = collectFieldNamesInSetters(psiClass);

        for (PsiField field : psiClass.getAllFields()) {
            if (field.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                continue;
            }
            if (shouldFieldBeAutowired(psiClass, field, fieldNamesInConstructors, fieldNamesInSetters)) {
                warnMissingAutowired(field, psiClass,project);
            }
        }
    }

    /**
     * Collects names of fields used in constructors of a class.
     *
     * @param psiClass the class to inspect
     * @return a set of field names used in constructors
     */
    private Set<String> collectFieldNamesInConstructors(PsiClass psiClass) {
        Set<String> fieldNamesInConstructors = new HashSet<>();
        PsiMethod[] constructors = psiClass.getConstructors();
        if (constructors.length == 1) {
            collectFieldNamesFromSingleConstructor(constructors[0], psiClass, fieldNamesInConstructors);
        } else if (constructors.length > 1) {
            collectFieldNamesFromMultipleConstructors(constructors, psiClass, fieldNamesInConstructors);
        }
        return fieldNamesInConstructors;
    }

    /**
     * Collects names of fields used in a single constructor of a class.
     *
     * @param constructor              the constructor to inspect
     * @param psiClass                 the class to which the constructor belongs
     * @param fieldNamesInConstructors the set to store collected field names
     */
    private void collectFieldNamesFromSingleConstructor(PsiMethod constructor, PsiClass psiClass, Set<String> fieldNamesInConstructors) {
        for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
            PsiField field = psiClass.findFieldByName(parameter.getName(), false);
            if (field != null && !field.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                fieldNamesInConstructors.add(field.getName());
            }
        }
    }

    /**
     * Collects names of fields used in multiple constructors of a class.
     *
     * @param constructors             the constructors to inspect
     * @param psiClass                 the class to which the constructors belong
     * @param fieldNamesInConstructors the set to store collected field names
     */
    private void collectFieldNamesFromMultipleConstructors(PsiMethod[] constructors, PsiClass psiClass, Set<String> fieldNamesInConstructors) {
        for (PsiMethod constructor : constructors) {
            if (constructor.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
                    PsiField field = psiClass.findFieldByName(parameter.getName(), false);
                    if (field != null && !field.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                        fieldNamesInConstructors.add(field.getName());
                    }
                }
            }
        }
    }

    /**
     * Collects names of fields used in setter methods of a class.
     *
     * @param psiClass the class to inspect
     * @return a set of field names used in setters
     */
    private Set<String> collectFieldNamesInSetters(PsiClass psiClass) {
        Set<String> fieldNamesInSetters = new HashSet<>();
        for (PsiMethod method : psiClass.getMethods()) {
            if (isSetter(method) && method.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                for (PsiParameter parameter : method.getParameterList().getParameters()) {
                    PsiField field = psiClass.findFieldByName(parameter.getName(), false);
                    if (field != null && !field.hasAnnotation(AUTOWIRED_ANNOTATION)) {
                        fieldNamesInSetters.add(field.getName());
                    }
                }
            }
        }
        return fieldNamesInSetters;
    }

    /**
     * Logs a warning if a field is missing the @Autowired annotation but should have it.
     *
     * @param field    the field to inspect
     * @param psiClass the class to which the field belongs
     */
    private void warnMissingAutowired(PsiField field, PsiClass psiClass,Project project) {
        String message = "Field " + field.getName() + " in class " + psiClass.getQualifiedName() +
                " should be annotated with @Autowired as it is not part of any constructor or setter but is used in methods.";
        showErrorNotification(message, project);
        logger.warn(message);

    }


    private boolean shouldFieldBeAutowired(PsiClass psiClass, PsiField field, Set<String> fieldNamesInConstructors, Set<String> fieldNamesInSetters) {
        // Check if the field is used in any method other than constructors and setters
        for (PsiMethod method : psiClass.getMethods()) {
            if (!method.isConstructor() && !isSetter(method) && isFieldUsedInMethod(field, method)) {
                return !fieldNamesInConstructors.contains(field.getName()) &&
                        !fieldNamesInSetters.contains(field.getName());
            }
        }
        return false;
    }

    /**
     * Checks if a field is used in a given method.
     *
     * @param field  the field to inspect
    //     * @param method the method to inspect
     * @return true if the field is used in the method, false otherwise
     */

    private boolean isFieldUsedInMethod(PsiField field, PsiMethod method) {
        PsiCodeBlock body = method.getBody();
        if (body != null) {
            FieldUsageVisitor visitor = new FieldUsageVisitor(field);
            body.accept(visitor);
            return visitor.isFieldUsed();
        }
        return false;
    }

    private static class FieldUsageVisitor extends JavaRecursiveElementWalkingVisitor {
        private final PsiField field;
        private boolean fieldUsed;

        public FieldUsageVisitor(PsiField field) {
            this.field = field;
            this.fieldUsed = false;
        }

        @Override
        public void visitReferenceExpression(@NotNull PsiReferenceExpression expression) {
            super.visitReferenceExpression(expression);
            if (expression.resolve() == field) {
                fieldUsed = true;
            }
        }

        public boolean isFieldUsed() {
            return fieldUsed;
        }
    }


    /**
     * Checks if a method is a setter method.
     *
     * @param method the method to inspect
     * @return true if the method is a setter, false otherwise
     */
    private boolean isSetter(PsiMethod method) {
        String methodName = method.getName();
        PsiParameterList parameterList = method.getParameterList();
        if (methodName.startsWith("set") && parameterList.getParametersCount() > 0) {
            PsiType returnType = method.getReturnType();
            return returnType != null && "void".equals(returnType.getCanonicalText());
        }
        return false;
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

        GlobalSearchScope scope = GlobalSearchScope.allScope(project);
        logger.info("Searching for class: " + className + " in scope: " + scope);

        PsiClass[] classes = JavaPsiFacade.getInstance(project).findClasses(className, scope);

        if (classes.length == 0) {
            if (className.contains("test") || className.contains("Test")) {
                logger.warn("No test classes found with name: " + className);
            } else {
                logger.warn("No classes found with name: " + className);
            }
            return null;
        }

        logger.info("Class found: " + classes[0].getQualifiedName());
        return classes[0];
    }
}