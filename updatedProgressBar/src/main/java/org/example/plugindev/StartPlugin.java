package org.example.plugindev;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
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
            ConsoleProgressIndicator indicator = new ConsoleProgressIndicator(4); // Number of major steps
            handleProject(project, indicator);
        }
    }


    /**
     * Handles the processing of the project files.
     *
     * @param project   the project to process
     */
    private void handleProject(Project project, @NotNull ConsoleProgressIndicator indicator) {
        List<PsiFile> psiFiles = new ArrayList<>();
        List<VirtualFile> xmlFiles = new ArrayList<>();
        Set<String> packages = new HashSet<>();

        String basePath = project.getBasePath();
        if (basePath != null) {
            findSpringXmlFiles(basePath, xmlFiles);
        }

        indicator.setFraction(0.25);
        indicator.setText("Extracting packages from Spring XML files...");
        findSpringXmlPackages(xmlFiles, packages);

        indicator.setFraction(0.50);
        indicator.setText("Collecting Java files from packages...");
        collectJavaFilesFromPackages(basePath, project, psiFiles, packages);

        indicator.setFraction(0.75);
        AnnotationChecker annotationChecker = new AnnotationChecker();
        indicator.setText("Checking annotations in project...");
        checkAnnotationsInProject(project, annotationChecker);

        Set<String> usedClasses = annotationChecker.getBeanClassNames();

        Set<String> packageNames = extractPackageNames(usedClasses);

//        System.out.println("Used Classes:");
//        for (String className : usedClasses) {
//            System.out.println(className);
//        }
//
//        System.out.println("Extracted Package Names:");
//        for (String packageName : packageNames) {
//            System.out.println(packageName);
//        }
//
        indicator.setFraction(1.0);
        indicator.setText("Finalizing checks...");
        Class<? extends List> psiClass = psiFiles.getClass();
        PackageChecker.initialize(project, psiClass);
        PackageChecker.checkPackagesDefinedInXml(packageNames, packages);
    }

    /**
     * Processes the base path to collect XML files.
     *
     * @param basePath the base path of the project
     * @param xmlFiles the list to collect XML files
     */

    private void findSpringXmlFiles(String basePath, List<VirtualFile> xmlFiles) {
        VirtualFile baseDir = LocalFileSystem.getInstance().findFileByPath(basePath);
        if (baseDir != null) {
            VirtualFile resourcesDir = baseDir.findFileByRelativePath("src/main/resources/spring");
            if (resourcesDir != null) {
                collectXmlFiles(resourcesDir, xmlFiles);
            } else {
                logger.warn("Resources directory not found at: " + basePath + "/src/main/resources/spring");
            }
        } else {
            logger.warn("Base directory not found at: " + basePath);
        }
    }


    /**
     * Processes the collected XML files to extract package information.
     *
     * @param xmlFiles the list of XML files to process
     * @param packages the set to collect package names
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
     * Collects Java files from the specified packages.
     *
     * @param basePath the base path of the project
     * @param project the project to process
     * @param psiFiles the list to collect PsiFiles
     * @param packages the set of package names to process
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
     * Checks annotations in the project's Java files.
     *
     * @param project the project to process
     * @param annotationChecker the annotation checker to use
     */
    private void checkAnnotationsInProject(Project project, AnnotationChecker annotationChecker) {
        Set<String> classNames = getClassNamesFromProject(project);
        for (String className : classNames) {
            PsiClass psiClass = AnnotationChecker.findPsiClassByName(className, project);
            if (psiClass != null) {
                annotationChecker.checkAnnotations(psiClass, project);
            }
        }
    }

    /**
     * Gathers all class names from the project.
     *
     * @param project the project to process
     * @return the set of class names
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
     * Recursively processes directories to find Java files and collect class names.
     *
     * @param dir the directory to process
     * @param classNames the set to collect class names
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
     * Extracts the fully qualified class name from a Java file.
     *
     * @param file the Java file to process
     * @return the fully qualified class name, or null if it cannot be determined
     */
    private String getClassNameFromFile(VirtualFile file) {
        // Assuming the file path corresponds to the class's package structure
        String relativePath = file.getPath().substring(file.getPath().indexOf("src/main/java/") + "src/main/java/".length());
        return relativePath.replace('/', '.').replace(".java", "");
    }

    /**
     * Extracts package names from a set of class names.
     *
     * @param classNames the set of class names
     * @return the set of package names
     */
    public static Set<String> extractPackageNames(Set<String> classNames) {
        Set<String> packageNames = new HashSet<>();
        for (String className : classNames) {
            int lastDotIndex = className.lastIndexOf('.');
            if (lastDotIndex != -1) {
                String packageName = className.substring(0, lastDotIndex);
                packageNames.add(packageName);
            }
        }
        return packageNames;
    }

    /**
     * Collects XML files from the specified directory.
     *
     * @param dir the directory to process
     * @param xmlFiles the list to collect XML files
     */

    private void collectXmlFiles(VirtualFile dir, List<VirtualFile> xmlFiles) {
        Stack<VirtualFile> stack = new Stack<>();
        stack.push(dir);

        while (!stack.isEmpty()) {
            VirtualFile currentDir = stack.pop();
            for (VirtualFile child : currentDir.getChildren()) {
                if (child.isDirectory()) {
                    stack.push(child);
                } else if (child.getName().endsWith(".xml")) {
                    xmlFiles.add(child);
                    logger.info("XML file found: " + child.getPath());
                }
            }
        }
    }


    /**
     * Collects Java files from the specified package directory.
     *
     * @param dir the directory to process
     * @param project the project to process
     * @param psiFiles the list to collect PsiFiles
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
                    PsiFile psiFile = psiManager.findFile(child);
                    if (psiFile != null) {
                        psiFiles.add(psiFile);
                    }
                }
            }
        }
    }

    /**
     * Checks if a file is a Spring XML file.
     *
     * @param file the file to check
     * @return true if the file is a Spring XML file, false otherwise
     */
    private boolean isSpringXmlFile(VirtualFile file) {
        String fileName = file.getName().toLowerCase();
        return fileName.contains("spring") && fileName.endsWith(".xml");
    }

    /**
     * Extracts package names from a Spring XML file.
     *
     * @param xmlFile the XML file to process
     * @return the set of package names defined in the XML file
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


}
