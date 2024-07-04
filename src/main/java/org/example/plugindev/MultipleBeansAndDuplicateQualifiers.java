package org.example.plugindev;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.search.searches.ClassInheritorsSearch;

import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.Query;
import org.jetbrains.annotations.NotNull;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;

import java.util.*;

/**
 * This class provides methods to check for multiple beans and duplicate qualifiers in a given project.
 */
public class MultipleBeansAndDuplicateQualifiers {

    private static final Logger logger = Logger.getInstance(MultipleBeansAndDuplicateQualifiers.class);
    private static final String QUALIFIER_ANNOTATION = "org.springframework.beans.factory.annotation.Qualifier";
    private static final String AUTOWIRED_ANNOTATION = "org.springframework.beans.factory.annotation.Autowired";
    private static final String PRIMARY_ANNOTATION = "org.springframework.context.annotation.Primary";
    private final Map<String,PsiClass>qualifierToClass=new HashMap<>();


    private static final Set<String> ANNOTATION_SET = new HashSet<>();
    private final Set<String> qualifierNames = new HashSet<>();

    static {
        ANNOTATION_SET.add("org.springframework.stereotype.Service");
        ANNOTATION_SET.add("org.springframework.stereotype.Component");
        ANNOTATION_SET.add("org.springframework.stereotype.Repository");
        ANNOTATION_SET.add("org.springframework.stereotype.Controller");
    }

    private void showErrorNotification(String message, Project project) {
        Notification notification = new Notification(
                "annotationCheckerGroup",
                "Multiple Bean detector",
                message,
                NotificationType.ERROR
        );
        Notifications.Bus.notify(notification, project);
        throw new RuntimeException("Error notification shown: " + message);
    }


    /**
     * Checks for qualifier annotations in the constructors, methods, and fields of the given class.
     *
     * @param psiClass the class to inspect
     * @param project  the current project
     */
    public void checkQualifier(@NotNull PsiClass psiClass, @NotNull Project project) {
        checkConstructorsForQualifiers(psiClass, project);
        checkMethodsForQualifiers(psiClass, project);
        checkFieldsForQualifiers(psiClass, project);
    }

    /**
     * Checks the constructors of the given class for qualifier annotations.
     *
     * @param psiClass the class to inspect
     * @param project  the current project
     */
//    private void checkConstructorsForQualifiers(@NotNull PsiClass psiClass, @NotNull Project project) {
//        PsiMethod[] constructors = psiClass.getConstructors();
//
//        if (constructors.length == 1) {
//            // If there is only one constructor, check its parameters regardless of @Autowired
//            PsiMethod constructor = constructors[0];
//            for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
//                checkParameterForQualifierNeed(parameter, project,psiClass);
//            }
//        } else {
//            // If there are multiple constructors, check only those with @Autowired annotation
//            for (PsiMethod constructor : constructors) {
//                if (hasAutowiredAnnotation(constructor)) {
//                    for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
//                        checkParameterForQualifierNeed(parameter, project,psiClass);
//                    }
//                }
//            }
//        }
//    }

    private void checkConstructorsForQualifiers(@NotNull PsiClass psiClass, @NotNull Project project) {
        PsiMethod[] constructors = psiClass.getConstructors();

        if (constructors.length == 1) {
            PsiMethod constructor = constructors[0];
            for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
                checkParameterForQualifierNeed(parameter, project, psiClass);
            }
        } else {
            for (PsiMethod constructor : constructors) {
                if (hasAutowiredAnnotation(constructor)) {
                    for (PsiParameter parameter : constructor.getParameterList().getParameters()) {
                        checkParameterForQualifierNeed(parameter, project, psiClass);
                    }
                }
            }
        }
    }


    /**
     * Checks the methods of the given class for qualifier annotations.
     *
     * @param psiClass the class to inspect
     * @param project  the current project
     */
    private void checkMethodsForQualifiers(@NotNull PsiClass psiClass, @NotNull Project project) {
        for (PsiMethod method : psiClass.getMethods()) {
            if (isSetter(method) && hasAutowiredAnnotation(method)) {
                for (PsiParameter parameter : method.getParameterList().getParameters()) {
                    checkParameterForQualifierNeed(parameter, project,psiClass);
                }
            }
        }
    }

    /**
     * Checks the fields of the given class for qualifier annotations.
     *
     * @param psiClass the class to inspect
     * @param project  the current project
     */
    private void checkFieldsForQualifiers(@NotNull PsiClass psiClass, @NotNull Project project) {
        for (PsiField field : psiClass.getAllFields()) {
            if (hasAutowiredAnnotation(field)) {
//                showOnStatusBar("Checking field: " + field.getName(), project);
                checkFieldForQualifierNeed(field, project,psiClass);
            }
        }
    }

    /**
     * Determines if a method is a setter method.
     *
     * @param method the method to inspect
     * @return true if the method is a setter, false otherwise
     */
    private boolean isSetter(PsiMethod method) {
        return method.getName().startsWith("set") &&
                method.getParameterList().getParametersCount() == 1 &&
                "void".equals(Objects.requireNonNull(method.getReturnType()).getCanonicalText());
    }

    /**
     * Checks if an element has the @Autowired annotation.
     *
     * @param element the element to inspect
     * @return true if the element has the @Autowired annotation, false otherwise
     */
    private static boolean hasAutowiredAnnotation(PsiModifierListOwner element) {
        PsiModifierList modifierList = element.getModifierList();
        return modifierList != null && modifierList.findAnnotation(AUTOWIRED_ANNOTATION) != null;
    }

    /**
     * Checks if a field needs a qualifier annotation.
     *
     * @param field   the field to inspect
     * @param project the current project
     */
    private void checkFieldForQualifierNeed(PsiField field, Project project,PsiClass psiClass) {
        PsiType fieldType = field.getType();
        if (isCollectionType(fieldType)) {
            PsiClass fieldClass = ((PsiClassType) fieldType).resolve();
            if (fieldClass != null && (fieldClass.isInterface() || fieldClass.hasModifierProperty(PsiModifier.ABSTRACT))) {
                checkChildClassesForQualifierNeed(fieldClass, project, field, null,psiClass);
            }
        }
    }

    /**
     * Checks if a parameter needs a qualifier annotation.
     *
     * @param parameter the parameter to inspect
     * @param project   the current project
     */
    private void checkParameterForQualifierNeed(PsiParameter parameter, Project project,PsiClass psiClass) {
        PsiType parameterType = parameter.getType();
        if (isCollectionType(parameterType)) {
            PsiClass parameterClass = ((PsiClassType) parameterType).resolve();
            if (parameterClass != null && (parameterClass.isInterface() || parameterClass.hasModifierProperty(PsiModifier.ABSTRACT))) {
                checkChildClassesForQualifierNeed(parameterClass, project, null, parameter,psiClass);
            }
        }
    }

    /**
     * Checks child classes for the need for a qualifier annotation.
     *
     * @param parentClass   the parent class
     * @param project       the current project
     * @param field         the field to inspect (optional)
     * @param parameter     the parameter to inspect (optional)
     * @param psiClass      the psiClass to inspect
     */

    private void checkChildClassesForQualifierNeed(@NotNull PsiClass parentClass, @NotNull Project project, PsiField field, PsiParameter parameter, PsiClass psiClass) {
        List<PsiClass> allChildClasses = findAllChildClasses(parentClass, project);

        if(allChildClasses.size()==1)
        {
            if (field != null) {
                PsiModifierList fieldModifiers = field.getModifierList();
                if (fieldModifiers != null) {
                    PsiAnnotation qualifierAnnotation = findQualifierAnnotation(fieldModifiers);
                    if (qualifierAnnotation != null) {
                        handleQualifierAnnotation(qualifierAnnotation, "Field " + field.getName() + " defined in class: "+psiClass.getName(), allChildClasses, project);
                        return;
                    }
                }
            }
            if (parameter != null) {
                PsiModifierList parameterModifiers = parameter.getModifierList();
                if (parameterModifiers != null) {
                    PsiAnnotation qualifierAnnotation = findQualifierAnnotation(parameterModifiers);
                    if (qualifierAnnotation != null) {
                        handleQualifierAnnotation(qualifierAnnotation, "Parameter " + parameter.getName() + " defined in class: "+psiClass.getName(), allChildClasses, project);
                        return;
                    }
                }
            }
        }

        if (allChildClasses.size()>1) {
            List<PsiClass> serviceAnnotatedClasses = findServiceAnnotatedClasses(allChildClasses);

            if (field != null) {
                PsiModifierList fieldModifiers = field.getModifierList();
                if (fieldModifiers != null) {
                    PsiAnnotation qualifierAnnotation = findQualifierAnnotation(fieldModifiers);
                    if (qualifierAnnotation != null) {
                        handleQualifierAnnotation(qualifierAnnotation, "Field " + field.getName() + " defined in class: "+psiClass.getName(), allChildClasses, project);
                        return;
                    }
                }
            }
            if (parameter != null) {
                PsiModifierList parameterModifiers = parameter.getModifierList();
                if (parameterModifiers != null) {
                    PsiAnnotation qualifierAnnotation = findQualifierAnnotation(parameterModifiers);
                    if (qualifierAnnotation != null) {
                        handleQualifierAnnotation(qualifierAnnotation, "Parameter " + parameter.getName() + " defined in class: "+psiClass.getName(), allChildClasses, project);
                        return;
                    }
                }
            }
            serviceAnnotatedClasses.remove(psiClass);
            if (serviceAnnotatedClasses.size() > 1) {
                List<PsiClass> primaryAnnotatedClasses = findPrimaryAnnotatedClasses(serviceAnnotatedClasses);
                if (primaryAnnotatedClasses.size() > 1) {
                    logMultiplePrimaryAnnotations(primaryAnnotatedClasses, project,field,parameter,psiClass);
                } else if (primaryAnnotatedClasses.isEmpty()) {
                    if(field!=null)
                    {
                        String message = "Field: "+field.getName()+" has multiple beans found but neither @Qualifier nor @Primary annotation is present in :"+ psiClass+ " " +
                                " Please use either @Qualifier or @Primary in the following child classes: "+ getClassNames(allChildClasses);
                        logger.warn(message);
                        showErrorNotification(message, project);

                    }
                    if(parameter!=null)
                    {
                        String message = "Parameter: "+parameter.getName()+" has multiple beans found but neither @Qualifier nor @Primary annotation is present in :"+ psiClass+ " " +
                                " Please use either @Qualifier or @Primary in the following child classes: "+ getClassNames(allChildClasses);
                        logger.warn(message);
                        showErrorNotification(message, project);

                    }
                }
            }
            else if(serviceAnnotatedClasses.isEmpty()){
                if(field!=null)
                {
                    String message = "Field: "+field.getName()+" has multiple child class found but neither @Service nor @Primary annotation is present in :"+ psiClass+ " " +
                            " Please use either @Service or @Primary in the following child classes: "+ getClassNames(allChildClasses);
                    logger.warn(message);
                    showErrorNotification(message, project);

                }
                if(parameter!=null)
                {
                    String message = "Parameter: "+parameter.getName()+" has multiple child class found but neither @Service nor @Primary annotation is present in"+ psiClass+ " " +
                            " Please use either @Service or @Primary in the following child classes: "+ getClassNames(allChildClasses);
                    logger.warn(message);
                    showErrorNotification(message, project);

                }
            }
        }
    }


    /**
     * Returns a space-separated string of the fully qualified names of the given classes.
     *
     * @param classes the list of classes to process
     * @return a space-separated string of the fully qualified names of the classes
     */
    private String getClassNames(List<PsiClass> classes) {
        StringBuilder classNames = new StringBuilder();
        for (PsiClass psiClass : classes) {
            classNames.append(psiClass.getQualifiedName()).append(" ");
        }
        return classNames.toString().trim();
    }

    /**
     * Finds all child classes of a given parent class.
     *
     * @param parentClass the parent class
     * @param project     the current project
     * @return a list of all child classes
     */

    private List<PsiClass> findAllChildClasses(@NotNull PsiClass parentClass, @NotNull Project project) {
        Set<PsiClass> allChildClassesSet = new HashSet<>();
        GlobalSearchScope projectScope = GlobalSearchScope.allScope(project);
        findAllChildClassesRecursive(parentClass, projectScope, allChildClassesSet);
        return new ArrayList<>(allChildClassesSet);
    }

    private void findAllChildClassesRecursive(PsiClass parentClass, GlobalSearchScope projectScope, Set<PsiClass> allChildClassesSet) {
        Query<PsiClass> query = ClassInheritorsSearch.search(parentClass, projectScope, true);
        for (PsiClass childClass : query) {
            if (!allChildClassesSet.contains(childClass)) {
                if (!childClass.isInterface() && !childClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
                    allChildClassesSet.add(childClass);
                }

                if (childClass.isInterface() || childClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
                    findAllChildClassesRecursive(childClass, projectScope, allChildClassesSet);
                }
            }
        }
    }

    /**
     * Finds all service-annotated classes from a list of classes.
     *
     * @param allChildClasses the list of classes
     * @return a list of service-annotated classes
     */
    private List<PsiClass> findServiceAnnotatedClasses(List<PsiClass> allChildClasses) {
        List<PsiClass> serviceAnnotatedClasses = new ArrayList<>();
        for (PsiClass childClass : allChildClasses) {
            if (hasServiceAnnotation(childClass)) {
                serviceAnnotatedClasses.add(childClass);
            }
        }
        return serviceAnnotatedClasses;
    }


    /**
     * Finds all primary-annotated classes from a list of service-annotated classes.
     *
     * @param serviceAnnotatedClasses the list of service-annotated classes
     * @return a list of primary-annotated classes
     */
    private List<PsiClass> findPrimaryAnnotatedClasses(List<PsiClass> serviceAnnotatedClasses) {
        List<PsiClass> primaryAnnotatedClasses = new ArrayList<>();
        for (PsiClass serviceClass : serviceAnnotatedClasses) {
            if (hasPrimaryAnnotation(serviceClass)) {
                primaryAnnotatedClasses.add(serviceClass);
            }
        }
        return primaryAnnotatedClasses;
    }

    /**
     * Logs a warning message for multiple primary-annotated classes.
     *
     * @param primaryAnnotatedClasses the list of primary-annotated classes
     * @param project the project plugin runs on
     * @param field  a field in class
     * @param parameter a parameter in class
     * @param psiClass current class
     */
    private void logMultiplePrimaryAnnotations(List<PsiClass> primaryAnnotatedClasses,Project project,PsiField field, PsiParameter parameter,PsiClass psiClass) {
        StringBuilder classNames = new StringBuilder();
        for (PsiClass primaryClass : primaryAnnotatedClasses) {
            classNames.append(primaryClass.getQualifiedName()).append(" ");
        }
        if(field!=null)
        {
            String message="Current class: "+psiClass+" has field "+field.getName()+" which has"+ " Multiple child classes with @Primary annotation found: " + classNames;
            showErrorNotification(message, project);

        }
        if(parameter!=null)
        {
            String message="Current class: "+psiClass+" has parameter "+parameter.getName()+" which has"+ " Multiple child classes with @Primary annotation found: " + classNames;
            showErrorNotification(message, project);

        }
    }



    /**
     * Checks if a class has the @Primary annotation.
     *
     * @param psiClass the class to inspect
     * @return true if the class has the @Primary annotation, false otherwise
     */
    private static boolean hasPrimaryAnnotation(PsiClass psiClass) {
        PsiModifierList modifierList = psiClass.getModifierList();
        return modifierList != null && modifierList.findAnnotation(PRIMARY_ANNOTATION) != null;
    }


    /**
     * Finds the @Qualifier annotation in a modifier list.
     *
     * @param modifierList the modifier list to inspect
     * @return the @Qualifier annotation, or null if not found
     */
    private PsiAnnotation findQualifierAnnotation(PsiModifierList modifierList) {
        for (PsiAnnotation annotation : modifierList.getAnnotations()) {
            if (QUALIFIER_ANNOTATION.equals(annotation.getQualifiedName())) {
                return annotation;
            }
        }
        return null;
    }

    /**
     * Handles the @Qualifier annotation and checks its validity.
     *
     * @param qualifierAnnotation the @Qualifier annotation
     * @param elementDescription  the description of the element
     * @param allChildClasses     the list of all child classes
     * @param project             the current project
     */
    private void handleQualifierAnnotation(PsiAnnotation qualifierAnnotation, String elementDescription, List<PsiClass> allChildClasses, Project project) {
        String qualifierName = getQualifierName(qualifierAnnotation);
        if (qualifierName == null || qualifierName.trim().isEmpty()) {
            String errorMessage = elementDescription + " has a @Qualifier annotation but it is missing a name. Please provide a name for the @Qualifier annotation.";
            logger.warn(errorMessage);
            showErrorNotification(errorMessage, project);

        }
        else if (!isValidQualifierName(qualifierName, allChildClasses,project)) {
            String errorMessage = elementDescription + " has a @Qualifier annotation with an invalid name.";
            logger.warn(errorMessage);
            showErrorNotification(errorMessage, project);
        }
//        else {
//            checkAndLogClassWithQualifierName(qualifierName, project);
//        }
    }
    /**
     * Checks and logs the class with a given qualifier name.
     *
     * @param qualifierName the qualifier name
     * @param project       the current project
     */
//    private void checkAndLogClassWithQualifierName(String qualifierName, Project project) {
//
//        String qualifiedClassName = capitalize(qualifierName);
//        PsiClass psiClass = findClassByName(project, qualifiedClassName);
//
//        if(psiClass == null)
//        {
//            psiClass=qualifierToClass.get(qualifierName);
//        }
//
//        if (psiClass != null && !psiClass.isInterface() && !psiClass.hasModifierProperty(PsiModifier.ABSTRACT)) {
//            qualifierNames.add(psiClass.getQualifiedName());
//            if (hasServiceAnnotation(psiClass)) {
//                String message=psiClass + " is annotated with @Service, @Component, or @Repository annotation.";
//                logger.warn(message);
//            }
//            else {
//                String message=psiClass + " is not annotated with @Service, @Component, or @Repository annotation.";
//                logger.warn(message);
//                showErrorNotification(message, project);
//
//            }
//        }
//        else {
//            System.out.println("Class " + qualifiedClassName + " not found!");
//        }
//    }


    /**
     * Gets the set of qualifier names.
     *
     * @return the set of qualifier names
     */
    public Set<String> getQualifierNames() {
        return qualifierNames;
    }

    /**
     * Finds a class by its name.
     *
     * @param project   the current project
     * @param className the name of the class
     * @return the class, or null if not found
     */
    private static PsiClass findClassByName(Project project, String className) {
        PsiClass[] classes = PsiShortNamesCache.getInstance(project).getClassesByName(className, GlobalSearchScope.allScope(project));
        return classes.length > 0 ? classes[0] : null;
    }

    /**
     * Capitalizes the first letter of a string.
     *
     * @param name the string to capitalize
     * @return the capitalized string
     */
    private static String capitalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        char[] chars = name.toCharArray();
        chars[0] = Character.toUpperCase(chars[0]);
        return new String(chars);
    }

    /**
     * Gets the name from a @Qualifier annotation.
     *
     * @param qualifierAnnotation the @Qualifier annotation
     * @return the name, or null if not found
     */
    private static String getQualifierName(PsiAnnotation qualifierAnnotation) {
        PsiAnnotationMemberValue value = qualifierAnnotation.findAttributeValue("value");
        return value != null ? value.getText().replaceAll("\"", "").trim() : null;
    }

    /**
     * Checks if a qualifier name is valid.
     *
     * @param qualifierName  the qualifier name
     * @param allChildClasses the list of all child classes
     * @return true if the qualifier name is valid, false otherwise
     */
    private boolean isValidQualifierName(String qualifierName, List<PsiClass> allChildClasses,Project project) {
        for (PsiClass childClass : allChildClasses) {
            if (qualifierName.equals(decapitalize(childClass.getName())) || hasMatchingClassQualifierAnnotation(qualifierName, childClass) || isQualifierNameInXml(qualifierName, project)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a class has a matching @Qualifier annotation.
     *
     * @param qualifierName the qualifier name
     * @param childClass    the class to inspect
     * @return true if the class has a matching @Qualifier annotation, false otherwise
     */
    private boolean hasMatchingClassQualifierAnnotation(String qualifierName, PsiClass childClass) {
        PsiModifierList classModifierList = childClass.getModifierList();
        if (classModifierList != null) {
            PsiAnnotation classQualifierAnnotation = classModifierList.findAnnotation(QUALIFIER_ANNOTATION);
            if (classQualifierAnnotation != null) {
                qualifierToClass.put(qualifierName, childClass);
                String classQualifierName = getQualifierName(classQualifierAnnotation);
                return qualifierName.equals(classQualifierName);
            }
        }
        return false; // No @Qualifier annotation found
    }

    private boolean isQualifierNameInXml(String qualifierName, Project project) {
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);
        Collection<VirtualFile> xmlFiles = FilenameIndex.getAllFilesByExt(project, "xml", scope);

        for (VirtualFile xmlFile : xmlFiles) {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(xmlFile);
            if (psiFile instanceof XmlFile xmlPsiFile) {
                XmlTag rootTag = xmlPsiFile.getRootTag();
                if (rootTag != null) {
                    for (XmlTag beanTag : rootTag.findSubTags("bean")) {
                            String id = beanTag.getAttributeValue("id");
                            if (qualifierName.equals(id)) {
                                return true;
                            }
                    }
                }
            }
        }
        return false;
    }


    /**
     * Decapitalizes the first letter of a string.
     *
     * @param name the string to decapitalize
     * @return the decapitalized string
     */
    private static String decapitalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        char[] chars = name.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

    /**
     * Checks if a type is a collection type.
     *
     * @param type the type to inspect
     * @return true if the type is a collection type, false otherwise
     */
    private static boolean isCollectionType(PsiType type) {
        if (type instanceof PsiClassType) {
            PsiClass resolvedClass = ((PsiClassType) type).resolve();
            if (resolvedClass != null) {
                String className = resolvedClass.getQualifiedName();
                return !"java.util.List".equals(className) && !"java.util.Set".equals(className);
            }
        }
        return true;
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

    /**
     * Retrieves the map that associates qualifier names with their corresponding PsiClass instances.
     *
     * @return a map where the keys are qualifier names (as strings) and the values are PsiClass instances
     */
    public Map<String, PsiClass> getQualifierMap() {
        return qualifierToClass;
    }

}