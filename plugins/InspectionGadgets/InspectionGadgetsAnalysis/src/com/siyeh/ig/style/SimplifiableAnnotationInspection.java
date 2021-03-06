/*
 * Copyright 2010-2014 Bas Leijdekkers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.style;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.HashSet;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

public class SimplifiableAnnotationInspection extends BaseInspection {

  @Nls
  @NotNull
  @Override
  public String getDisplayName() {
    return InspectionGadgetsBundle.message("simplifiable.annotation.display.name");
  }

  @NotNull
  @Override
  protected String buildErrorString(Object... infos) {
    return InspectionGadgetsBundle.message("simplifiable.annotation.problem.descriptor");
  }

  @Override
  protected InspectionGadgetsFix buildFix(Object... infos) {
    final PsiAnnotation annotation = (PsiAnnotation)infos[0];
    return new SimplifiableAnnotationFix(annotation);
  }

  private static class SimplifiableAnnotationFix extends InspectionGadgetsFix {

    private final String replacement;

    public SimplifiableAnnotationFix(PsiAnnotation annotation) {
      this.replacement = buildAnnotationText(annotation, new StringBuilder()).toString();
    }

    @Override
    @NotNull
    public String getName() {
      return InspectionGadgetsBundle.message("simplifiable.annotation.quickfix",
                                             StringUtil.shortenTextWithEllipsis(replacement, 50, 0, true));
    }
     @Override
    @NotNull
    public String getFamilyName() {
      return getName();
    }

    @Override
    protected void doFix(Project project, ProblemDescriptor descriptor) throws IncorrectOperationException {
      final PsiElement element = descriptor.getPsiElement();
      if (!(element instanceof PsiAnnotation)) {
        return;
      }
      final PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
      final PsiAnnotation annotation = factory.createAnnotationFromText(replacement, element);
      element.replace(annotation);
    }

    private static StringBuilder buildAnnotationText(PsiAnnotation annotation, StringBuilder out) {
      out.append('@');
      final PsiJavaCodeReferenceElement nameReferenceElement = annotation.getNameReferenceElement();
      assert nameReferenceElement != null;
      out.append(nameReferenceElement.getText());
      final PsiAnnotationParameterList parameterList = annotation.getParameterList();
      final PsiNameValuePair[] attributes = parameterList.getAttributes();
      if (attributes.length == 0) {
        return out;
      }
      out.append('(');
      if (attributes.length == 1) {
        final PsiNameValuePair attribute = attributes[0];
        @NonNls final String name = attribute.getName();
        if (name != null && !PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME.equals(name)) {
          out.append(name).append('=');
        }
        buildAttributeValueText(attribute.getValue(), out);
      }
      else {
        for (int i = 0; i < attributes.length; i++) {
          final PsiNameValuePair attribute = attributes[i];
          if (i > 0) {
            out.append(',');
          }
          out.append(attribute.getName()).append('=');
          buildAttributeValueText(attribute.getValue(), out);
        }
      }
      out.append(')');
      return out;
    }

    private static StringBuilder buildAttributeValueText(PsiAnnotationMemberValue value, StringBuilder out) {
      if (value instanceof PsiArrayInitializerMemberValue) {
        final PsiArrayInitializerMemberValue arrayValue = (PsiArrayInitializerMemberValue)value;
        final PsiAnnotationMemberValue[] initializers = arrayValue.getInitializers();
        if (initializers.length == 1) {
          return out.append(initializers[0].getText());
        }
      }
      else if (value instanceof PsiAnnotation) {
        return buildAnnotationText((PsiAnnotation)value, out);
      }
      return out.append(value.getText());
    }
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new SimplifiableAnnotationVisitor();
  }

  private static class SimplifiableAnnotationVisitor extends BaseInspectionVisitor {

    @Override
    public void visitAnnotation(PsiAnnotation annotation) {
      super.visitAnnotation(annotation);
      final PsiAnnotationParameterList parameterList = annotation.getParameterList();
      final PsiJavaCodeReferenceElement nameReferenceElement = annotation.getNameReferenceElement();
      if (nameReferenceElement == null) {
        return;
      }
      final PsiNameValuePair[] attributes = parameterList.getAttributes();
      final PsiElement[] annotationChildren = annotation.getChildren();
      if (annotationChildren.length >= 2 && annotationChildren[1] instanceof PsiWhiteSpace) {
        if (!containsError(annotation)) {
          registerError(annotation, annotation);
        }
      }
      else if (attributes.length == 0) {
        final PsiElement[] children = parameterList.getChildren();
        if (children.length <= 0) {
          return;
        }
        if (!containsError(annotation)) {
          registerError(annotation, annotation);
        }
      }
      else if (attributes.length == 1) {
        final PsiNameValuePair attribute = attributes[0];
        @NonNls final String name = attribute.getName();
        final PsiAnnotationMemberValue attributeValue = attribute.getValue();
        if (!PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME.equals(name)) {
          if (!(attributeValue instanceof PsiArrayInitializerMemberValue)) {
            return;
          }
          final PsiArrayInitializerMemberValue arrayValue = (PsiArrayInitializerMemberValue)attributeValue;
          final PsiAnnotationMemberValue[] initializers = arrayValue.getInitializers();
          if (initializers.length != 1) {
            return;
          }
        }
        if (!containsError(annotation)) {
          registerError(annotation, annotation);
        }
      }
      else if (attributes.length > 1) {
        for (PsiNameValuePair attribute : attributes) {
          final PsiAnnotationMemberValue value = attribute.getValue();
          if (!(value instanceof PsiArrayInitializerMemberValue)) {
            continue;
          }
          final PsiArrayInitializerMemberValue arrayInitializerMemberValue = (PsiArrayInitializerMemberValue)value;
          final PsiAnnotationMemberValue[] initializers = arrayInitializerMemberValue.getInitializers();
          if (initializers.length != 1) {
            continue;
          }
          if (!containsError(annotation)) {
            registerError(annotation, annotation);
          }
          return;
        }
      }
    }

    private static boolean containsError(PsiAnnotation annotation) {
      final PsiJavaCodeReferenceElement nameRef = annotation.getNameReferenceElement();
      if (nameRef == null) {
        return true;
      }
      final PsiClass aClass = (PsiClass)nameRef.resolve();
      if (aClass == null || !aClass.isAnnotationType()) {
        return true;
      }
      final Set<String> names = new HashSet<String>();
      final PsiAnnotationParameterList annotationParameterList = annotation.getParameterList();
      if (PsiUtilCore.hasErrorElementChild(annotationParameterList)) {
        return true;
      }
      final PsiNameValuePair[] attributes = annotationParameterList.getAttributes();
      for (PsiNameValuePair attribute : attributes) {
        final PsiReference reference = attribute.getReference();
        if (reference == null) {
          return true;
        }
        final PsiMethod method = (PsiMethod)reference.resolve();
        if (method == null) {
          return true;
        }
        final PsiAnnotationMemberValue value = attribute.getValue();
        if (value == null || PsiUtilCore.hasErrorElementChild(value)) {
          return true;
        }
        if (value instanceof PsiAnnotation && containsError((PsiAnnotation)value)) {
          return true;
        }
        if (!hasCorrectType(value, method.getReturnType())) {
          return true;
        }
        final String name = attribute.getName();
        if (!names.add(name != null ? name : PsiAnnotation.DEFAULT_REFERENCED_METHOD_NAME)) {
          return true;
        }
      }

      for (PsiMethod method : aClass.getMethods()) {
        if (!(method instanceof PsiAnnotationMethod)) {
          continue;
        }
        final PsiAnnotationMethod annotationMethod = (PsiAnnotationMethod)method;
        if (annotationMethod.getDefaultValue() == null && !names.contains(annotationMethod.getName())) {
          return true; // missing a required argument
        }
      }
      return false;
    }

    private static boolean hasCorrectType(@Nullable PsiAnnotationMemberValue value, PsiType expectedType) {
      if (value == null) return false;

      if (expectedType instanceof PsiClassType &&
          expectedType.equalsToText(CommonClassNames.JAVA_LANG_CLASS) &&
          !(value instanceof PsiClassObjectAccessExpression)) {
        return false;
      }

      if (value instanceof PsiAnnotation) {
        final PsiJavaCodeReferenceElement nameRef = ((PsiAnnotation)value).getNameReferenceElement();
        if (nameRef == null) return true;

        if (expectedType instanceof PsiClassType) {
          final PsiClass aClass = ((PsiClassType)expectedType).resolve();
          if (aClass != null && nameRef.isReferenceTo(aClass)) return true;
        }

        if (expectedType instanceof PsiArrayType) {
          final PsiType componentType = ((PsiArrayType)expectedType).getComponentType();
          if (componentType instanceof PsiClassType) {
            final PsiClass aClass = ((PsiClassType)componentType).resolve();
            if (aClass != null && nameRef.isReferenceTo(aClass)) return true;
          }
        }
        return false;
      }
      if (value instanceof PsiArrayInitializerMemberValue) {
        return expectedType instanceof PsiArrayType;
      }
      if (value instanceof PsiExpression) {
        final PsiExpression expression = (PsiExpression)value;
        return expression.getType() != null && TypeConversionUtil.areTypesAssignmentCompatible(expectedType, expression) ||
               expectedType instanceof PsiArrayType &&
               TypeConversionUtil.areTypesAssignmentCompatible(((PsiArrayType)expectedType).getComponentType(), expression);
      }
      return true;
    }
  }
}
