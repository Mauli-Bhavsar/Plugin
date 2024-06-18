package org.example.plugindev;
import com.intellij.psi.*;

import java.util.Objects;

public class PayloadChecker {

    private static final String CONTROLLER_ANNOTATION = "org.springframework.stereotype.Controller";
    private static final String PATH_ANNOTATION = "javax.ws.rs.Path";
    private static final String GET_ANNOTATION = "javax.ws.rs.GET";

    public void checkAnnotation(PsiClass psiClass)
    {
        if ((psiClass.hasAnnotation(CONTROLLER_ANNOTATION)) &&(psiClass.hasAnnotation(PATH_ANNOTATION)) ) {
//              System.out.println(psiClass.getName() + " is annotated with @Controller and @Path");
                PsiMethod[] psiMethods =psiClass.getAllMethods();
                for(PsiMethod psiMethod:psiMethods)
                {
                    if(psiMethod.hasAnnotation(GET_ANNOTATION))
                    {
                       System.out.println(psiMethod.getName() + " in " + psiClass.getName() + " is annotated with @GET");
                        PsiParameter[] parameters = psiMethod.getParameterList().getParameters();
                        for (PsiParameter parameter : parameters) {
                            if (!hasAnyAnnotation(parameter)) {
//                                System.out.println("Parameter " + parameter.getName() + " in method " + psiMethod.getName() +
//                                        " has annotations");
                                System.out.println("Parameter " + parameter.getName() + " in method " + Objects.requireNonNull(psiMethod.getContainingClass()).getQualifiedName() +
                                        " has no annotations");
                            }
                        }
                    }
                }
        }
    }

    private boolean hasAnyAnnotation(PsiModifierListOwner psiElement) {
        PsiAnnotation[] annotations = psiElement.getAnnotations();
        return annotations.length > 0;
    }
}
