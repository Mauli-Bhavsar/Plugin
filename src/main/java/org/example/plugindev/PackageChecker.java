package org.example.plugindev;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;


import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Set;

/**
 * A utility class for checking package definitions in XML files against usage in the project.
 * It provides methods to initialize the checker, display error notifications, and check packages.
 */
public class PackageChecker {

    private static Project project;
    static Class<? extends List> psiClass;
    private static final Logger logger = Logger.getInstance(PackageChecker.class);


    /**
     * Initializes the PackageChecker with the specified project and PSI class.
     *
     * @param projectInstance The project instance to be used.
     * @param psiClassInstance The PSI class instance to be used.
     * @throws IllegalArgumentException If either projectInstance or psiClassInstance is null.
     */
    public static void initialize(@NotNull Project projectInstance, Class<? extends List> psiClassInstance) {
        if (psiClassInstance == null) {
            throw new IllegalArgumentException("Project and PsiClass must not be null");
        }
        project = projectInstance;
        psiClass = psiClassInstance;
        logger.info("PackageChecker initialized with Project and PsiClass");
    }


    private static void showErrorNotification(String message) {
        if (project == null) {
            logger.error("Project must be initialized before showing notifications");
            return;
        }
        Notification notification = new Notification(
                "annotationCheckerGroup",
                "Package not found",
                message,
                NotificationType.ERROR
        );
        Notifications.Bus.notify(notification, project);
//        logger.info("Error notification shown: " + message);
        throw new RuntimeException("Error notification shown: " + message);
    }

    /**
     * Checks whether the packages used in the project are defined in the XML files.
     * If a used package is not defined in any XML file, it logs a warning and shows error notifications.
     *
     * @param usedPackages The set of packages used in the project.
     * @param xmlPackages The set of packages defined in the XML files.
     */
    public static void checkPackagesDefinedInXml(Set<String> usedPackages, Set<String> xmlPackages) {
        if (project == null || psiClass == null) {
            logger.error("Project and PsiClass must be initialized before checking packages");
            return;
        }
        for (String usedPackage : usedPackages) {
            if (!xmlPackages.contains(usedPackage) && !isStandardJavaPackage(usedPackage)) {
                String message = "Package " + usedPackage + " is used but not defined in any XML file.";
                // Log the warning message
                logger.warn(message);

                // Show notifications
                showErrorNotification(message);
            }
        }
    }

    private static boolean isStandardJavaPackage(String packageName) {
        return packageName.startsWith("java.") || packageName.startsWith("javax.");
    }

}