package org.example.plugindev;

import com.intellij.psi.*;

import com.intellij.openapi.project.Project;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public class PayloadChecker {

    private static final String CONTROLLER_ANNOTATION = "org.springframework.stereotype.Controller";
    private static final String PATH_ANNOTATION = "javax.ws.rs.Path";
    private static final String GET_ANNOTATION = "javax.ws.rs.GET";

    private static final Set<String> SUPPORTED_ANNOTATIONS = new HashSet<>();

    static {
        SUPPORTED_ANNOTATIONS.add("javax.ws.rs.QueryParam");
        SUPPORTED_ANNOTATIONS.add("javax.ws.rs.FormParam");
        SUPPORTED_ANNOTATIONS.add("org.springframework.web.bind.annotation.RequestParam");
        SUPPORTED_ANNOTATIONS.add("org.springframework.web.bind.annotation.PathVariable");
        SUPPORTED_ANNOTATIONS.add("org.springframework.web.bind.annotation.RequestBody");
        SUPPORTED_ANNOTATIONS.add("org.springframework.web.bind.annotation.ModelAttribute");
    }

    private void showErrorNotification(String message, Project project) {
        Notification notification = new Notification(
                "annotationCheckerGroup",
                "Payload checker",
                message,
                NotificationType.ERROR
        );
        Notifications.Bus.notify(notification, project);
    }


    public void checkAnnotation(PsiClass psiClass, Project project) {
        if (psiClass.hasAnnotation(CONTROLLER_ANNOTATION) && psiClass.hasAnnotation(PATH_ANNOTATION)) {
            PsiMethod[] psiMethods = psiClass.getAllMethods();
            for (PsiMethod psiMethod : psiMethods) {
                if (psiMethod.hasAnnotation(GET_ANNOTATION)) {
                    System.out.println(psiMethod.getName() + " in " + psiClass.getName() + " is annotated with @GET");
                    PsiParameter[] parameters = psiMethod.getParameterList().getParameters();
                    for (PsiParameter parameter : parameters) {
                        if (!hasAnyAnnotation(parameter)) {
                            String errorMessage = "Parameter " + parameter.getName() + " in method " +
                                    Objects.requireNonNull(psiMethod.getContainingClass()).getQualifiedName() +
                                    " has no annotations";
                            System.out.println(errorMessage);
                            showErrorNotification(errorMessage, project);
                        }
                    }
                }
            }
        }
    }

    private boolean hasAnyAnnotation(PsiModifierListOwner psiElement) {
        PsiAnnotation[] annotations = psiElement.getAnnotations();
        for (PsiAnnotation annotation : annotations) {
            if (SUPPORTED_ANNOTATIONS.contains(annotation.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }
}
