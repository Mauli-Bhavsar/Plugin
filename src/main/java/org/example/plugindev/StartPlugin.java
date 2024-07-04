package org.example.plugindev;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import java.util.*;
import com.intellij.openapi.diagnostic.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * An IntelliJ IDEA plugin action that processes project files,
 * extracts information about packages and classes, and checks annotations.
 */
public class StartPlugin extends AnAction {

    private static final Logger logger = Logger.getInstance(StartPlugin.class);

    /**
     * Entry point for the action performed when the plugin is triggered.
     *
     * @param e the event representing the action
     */
    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project != null) {
            new Task.Backgroundable(project, "Processing project files") {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    long startTime = System.nanoTime();
                    Runtime runtime = Runtime.getRuntime();
                    long startMemory = runtime.totalMemory() - runtime.freeMemory();

                    try {
                        handleProject(project, indicator);
                    } catch (RuntimeException ex) {
                        if (ex.getMessage() == null || !ex.getMessage().startsWith("Error notification shown: ")) {
                            throw ex;
                        } else {
                            System.out.println("Exiting peacefully: " + ex.getMessage());
                        }
                    }
                    long endTime = System.nanoTime();
                    long elapsedTime = (endTime - startTime) / 1_000_000; // Convert to milliseconds
                    long endMemory = runtime.totalMemory() - runtime.freeMemory();
                    long usedMemory = (endMemory - startMemory) / (1024 * 1024); // Convert to megabytes

                    String notificationContent = "Elapsed time: " + elapsedTime + " ms\n" +
                            "Memory used: " + usedMemory + " MB";
                    showErrorNotification(notificationContent,project);
                }
            }.queue();
        }
    }

    private void showErrorNotification(String message, Project project) {
        Notification notification = new Notification(
                "annotationCheckerGroup",
                "Memory and time used",
                message,
                NotificationType.INFORMATION
        );
        Notifications.Bus.notify(notification, project);
    }

    /**
     * Handles the processing of the project files.
     *
     * @param project   the project to process
     * @param indicator progress bar indicator used
     */
    private void handleProject(Project project, @NotNull ProgressIndicator indicator) {
        indicator.setText("Starting plugin action");
        List<VirtualFile> springXmlFiles = new ArrayList<>();
        Set<String> packages = new HashSet<>();

        String basePath = project.getBasePath();
        if (basePath != null) {
            findModulesAndSpringXmlFiles(basePath, springXmlFiles);
        }

        indicator.setText("Finding Spring XML packages");
        findSpringXmlPackages(springXmlFiles, packages);

        indicator.setText("Processing packages and classes");
        processPackagesAndClasses(basePath, project, packages, indicator);
    }

    /**
     * Finds all the modules and collects Spring XML files in the given base path.
     *
     * @param basePath the base path of the project
     * @param springXmlFiles the list to store the found Spring XML files
     */

    private void findModulesAndSpringXmlFiles(String basePath, List<VirtualFile> springXmlFiles) {
        VirtualFile baseDir = LocalFileSystem.getInstance().findFileByPath(basePath);
        if (baseDir != null) {
            collectSpringXmlFiles(baseDir, springXmlFiles);
        } else {
            logger.warn("Base directory not found at: " + basePath);
        }
    }

    /**
     * Collects Spring XML files in the given directory recursively.
     *
     * @param dir the directory to search for Spring XML files
     * @param springXmlFiles the list to store the found Spring XML files
     */
    private void collectSpringXmlFiles(VirtualFile dir, List<VirtualFile> springXmlFiles) {
        Stack<VirtualFile> stack = new Stack<>();
        stack.push(dir);
        while (!stack.isEmpty()) {
            VirtualFile currentDir = stack.pop();
            for (VirtualFile child : currentDir.getChildren()) {
                if (child.isDirectory()) {
                    stack.push(child);
                } else if (child.getName().matches("spring-.*\\.xml")) {
                    springXmlFiles.add(child);
                    logger.info("Spring XML file found: " + child.getPath());
                }
            }
        }
    }

    /**
     * Finds packages defined in the given list of Spring XML files.
     *
     * @param xmlFiles the list of Spring XML files
     * @param packages the set to store the found packages
     */
    private void findSpringXmlPackages(List<VirtualFile> xmlFiles, Set<String> packages) {
        for (VirtualFile xmlFile : xmlFiles) {
            if (isSpringXmlFile(xmlFile)) {
                Set<String> xmlPackages = getPackagesFromXmlFile(xmlFile);
                packages.addAll(xmlPackages);
            }
        }
    }

    /**
     * Checks if the given file is a Spring XML file.
     *
     * @param file the file to check
     * @return true if the file is a Spring XML file, false otherwise
     */
    private boolean isSpringXmlFile(VirtualFile file) {
        String fileName = file.getName().toLowerCase();
        return fileName.contains("spring") && fileName.endsWith(".xml");
    }

    /**
     * Extracts packages from the given Spring XML file.
     *
     * @param xmlFile the Spring XML file to process
     * @return a set of packages found in the XML file
     */
    private Set<String> getPackagesFromXmlFile(VirtualFile xmlFile) {
        Set<String> packages = new HashSet<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(xmlFile.getInputStream());
            NodeList componentScanNodes = doc.getElementsByTagName("context:component-scan");

            for (int i = 0; i < componentScanNodes.getLength(); i++) {
                Element element = (Element) componentScanNodes.item(i);
                String basePackage = element.getAttribute("base-package");
                if (!basePackage.isEmpty()) {
                    String[] basePackages = basePackage.split(",");
                    for (String pkg : basePackages) {
                        packages.add(pkg.trim());
                    }
                }
            }
            logger.info("Packages found in " + xmlFile.getName() + ": " + packages);
        } catch (Exception ex) {
            logger.error("Error parsing XML file " + xmlFile.getName(), ex);
        }
        return packages;
    }


    /**
     * Processes the packages and classes in the given project. This method performs the following steps:
     * 1. Collects Java files from the specified packages.
     * 2. Checks for errors in the project using the {@link AnnotationChecker}.
     * 3. Processes the used classes to extract their package names.
     * 4. Prints the class names and their corresponding packages.
     * 5. Checks if the packages defined in the XML are used in the project.
     *
     * @param basePath  the base path of the project
     * @param project   the project to process
     * @param packages  the set of packages to process
     * @param indicator progress bar indicator used to show the current processing step
     */
    private void processPackagesAndClasses(String basePath, Project project, Set<String> packages, ProgressIndicator indicator) {
        List<PsiFile> psiFiles = new ArrayList<>();

        indicator.setText("Collecting Java files from packages");
        collectJavaFilesFromPackages(basePath, project, psiFiles, packages);

        indicator.setText("Checking for errors in project");
        AnnotationChecker annotationChecker = new AnnotationChecker();
        checkAnnotationsInProject(project, annotationChecker);

        indicator.setText("Processing used classes");
        Set<String> usedClasses = annotationChecker.getBeanClassNames();

        indicator.setText("Extracting package names");
        Set<String> packageNames = extractPackageNames(usedClasses);

        // Print class names and their packages
        System.out.println("Class Names and Their Packages:");
        for (String className : usedClasses) {
            System.out.println("Class: " + className + ", Package: " + getClassPackage(className));
        }

        for(String packageName : packageNames) {
            System.out.println("Used Package: " + packageName );
        }

        for(String getPackageName : packages) {
            System.out.println("Get Package: " + getPackageName );
        }

        indicator.setText("Checking packages defined in XML");
        PackageChecker.initialize(project, List.class);
        PackageChecker.checkPackagesDefinedInXml(packageNames, packages);
    }


    /**
     * Collects Java files from the given packages in the project.
     *
     * @param basePath the base path of the project
     * @param project the project to process
     * @param psiFiles the list to store the found Java files
     * @param packages the set of packages to search for Java files
     */
    private void collectJavaFilesFromPackages(String basePath, Project project, List<PsiFile> psiFiles, Set<String> packages) {
        for (String pkg : packages) {
            String packagePath = "src/main/java/" + pkg.replace('.', '/');
            String packageFullPath = basePath + "/" + packagePath;
            VirtualFile packageDir = LocalFileSystem.getInstance().findFileByPath(packageFullPath);
            if (packageDir != null) {
                collectJavaFilesInDirectory(packageDir, project, psiFiles);
            }
        }
    }

    /**
     * Collects Java files in the given directory recursively.
     *
     * @param dir the directory to search for Java files
     * @param project the project to process
     * @param psiFiles the list to store the found Java files
     */
    private void collectJavaFilesInDirectory(VirtualFile dir, Project project, List<PsiFile> psiFiles) {
        Stack<VirtualFile> stack = new Stack<>();
        stack.push(dir);
        PsiManager psiManager = PsiManager.getInstance(project);

        while (!stack.isEmpty()) {
            VirtualFile currentDir = stack.pop();
            for (VirtualFile child : currentDir.getChildren()) {
                if (child.isDirectory()) {
                    stack.push(child);
                } else if (child.getName().endsWith(".java")) {
                    ApplicationManager.getApplication().runReadAction(() -> {
                        PsiFile psiFile = psiManager.findFile(child);
                        if (psiFile != null) {
                            psiFiles.add(psiFile);
                        }
                    });
                }
            }
        }
    }

    /**
     * Checks annotations in the project using the given AnnotationChecker.
     *
     * @param project the project to process
     * @param annotationChecker the checker to use for checking annotations
     */
    private void checkAnnotationsInProject(Project project, AnnotationChecker annotationChecker) {
        Set<String> classNames = getClassNamesFromProject(project);
        for (String className : classNames) {
            ApplicationManager.getApplication().runReadAction(() -> {
                PsiClass psiClass = AnnotationChecker.findPsiClassByName(className, project);
                if (psiClass != null) {
                    annotationChecker.checkAnnotations(psiClass, project);
                }
            });
        }
    }

    /**
     * Collects all Java class names in the project.
     *
     * @param project the project to process
     * @return a set of class names found in the project
     */
    private Set<String> getClassNamesFromProject(Project project) {
        Set<String> classNames = new HashSet<>();
        VirtualFile baseDir = LocalFileSystem.getInstance().findFileByPath(Objects.requireNonNull(project.getBasePath()));
        if (baseDir != null) {
            collectJavaFiles(baseDir, classNames);
        }
        return classNames;
    }

    /**
     * Collects Java files in the given directory and extracts their class names.
     *
     * @param dir the directory to search for Java files
     * @param classNames the set to store the found class names
     */
    private void collectJavaFiles(VirtualFile dir, Set<String> classNames) {
        Stack<VirtualFile> stack = new Stack<>();
        stack.push(dir);

        while (!stack.isEmpty()) {
            VirtualFile currentDir = stack.pop();
            for (VirtualFile child : currentDir.getChildren()) {
                if (child.isDirectory()) {
                    stack.push(child);
                } else if (child.getName().endsWith(".java")) {
                    String className = getClassNameFromFile(child);
                    classNames.add(className);
                }
            }
        }
    }

    /**
     * Extracts the class name from the given Java file.
     *
     * @param file the Java file to process
     * @return the class name derived from the file path
     */
    private String getClassNameFromFile(VirtualFile file) {
        // Assuming the file path corresponds to the class's package structure
        String relativePath = file.getPath().substring(file.getPath().indexOf("src/main/java/") + "src/main/java/".length());
        return relativePath.replace('/', '.').replace(".java", "");
    }

    /**
     * Extracts the package name from the given class name.
     *
     * @param className the class name to process
     * @return the package name derived from the class name
     */
    private String getClassPackage(String className) {
        int lastDotIndex = className.lastIndexOf('.');
        return (lastDotIndex != -1) ? className.substring(0, lastDotIndex) : "";
    }

    /**
     * Extracts package names from the given set of class names.
     *
     * @param classNames the set of class names to process
     * @return a set of package names extracted from the class names
     */
    private Set<String> extractPackageNames(Set<String> classNames) {
        Set<String> packageNames = new HashSet<>();
        for (String className : classNames) {
            String packageName = getClassPackage(className);
            if (!packageName.isEmpty()) {
                packageNames.add(packageName);
            }
        }
        return packageNames;
    }
}