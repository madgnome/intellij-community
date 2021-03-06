/*
 * Copyright 2000-2014 JetBrains s.r.o.
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
package com.intellij.codeInspection.dataFlow;

import com.intellij.codeInspection.dataFlow.MethodContract.ValueConstraint;
import com.intellij.openapi.util.Condition;
import com.intellij.psi.*;
import com.intellij.psi.tree.IElementType;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

import static com.intellij.codeInspection.dataFlow.MethodContract.ValueConstraint.*;

/**
 * @author peter
 */
public class ContractInference {

  @NotNull
  public static List<MethodContract> inferContracts(@NotNull PsiMethod method) {
    return new ContractInferenceInterpreter(method).inferContracts();
  }
}

class ContractInferenceInterpreter {
  private final PsiMethod myMethod;

  public ContractInferenceInterpreter(PsiMethod method) {
    myMethod = method;
  }

  List<MethodContract> inferContracts() {
    PsiCodeBlock body = myMethod.getBody();
    if (body == null) return Collections.emptyList();

    PsiStatement[] statements = body.getStatements();
    if (statements.length == 0) return Collections.emptyList();

    ValueConstraint[] emptyState = MethodContract.createConstraintArray(myMethod.getParameterList().getParametersCount());
    return visitStatements(Collections.singletonList(emptyState), statements);
  }

  @NotNull
  private List<MethodContract> visitExpression(final List<ValueConstraint[]> states, @Nullable PsiExpression expr) {
    if (states.isEmpty()) return Collections.emptyList();
    if (states.size() > 300) return Collections.emptyList(); // too complex

    if (expr instanceof PsiPolyadicExpression) {
      PsiExpression[] operands = ((PsiPolyadicExpression)expr).getOperands();
      IElementType op = ((PsiPolyadicExpression)expr).getOperationTokenType();
      if (operands.length == 2 && (op == JavaTokenType.EQEQ || op == JavaTokenType.NE)) {
        return visitEqualityComparison(states, operands[0], operands[1], op == JavaTokenType.EQEQ);
      }
      if (op == JavaTokenType.ANDAND || op == JavaTokenType.OROR) {
        return visitLogicalOperation(operands, op == JavaTokenType.ANDAND, states);
      }
    }

    if (expr instanceof PsiConditionalExpression) {
      List<MethodContract> conditionResults = visitExpression(states, ((PsiConditionalExpression)expr).getCondition());
      return ContainerUtil.concat(
        visitExpression(antecedentsOf(filterReturning(conditionResults, TRUE_VALUE)), ((PsiConditionalExpression)expr).getThenExpression()),
        visitExpression(antecedentsOf(filterReturning(conditionResults, FALSE_VALUE)), ((PsiConditionalExpression)expr).getElseExpression()));
    }


    if (expr instanceof PsiParenthesizedExpression) {
      return visitExpression(states, ((PsiParenthesizedExpression)expr).getExpression());
    }

    if (expr instanceof PsiPrefixExpression && ((PsiPrefixExpression)expr).getOperationTokenType() == JavaTokenType.EXCL) {
      List<MethodContract> result = ContainerUtil.newArrayList();
      for (MethodContract contract : visitExpression(states, ((PsiPrefixExpression)expr).getOperand())) {
        if (contract.returnValue == TRUE_VALUE || contract.returnValue == FALSE_VALUE) {
          result.add(new MethodContract(contract.arguments, negateConstraint(contract.returnValue)));
        }
      }
      return result;
    }

    if (expr instanceof PsiInstanceOfExpression) {
      final int parameter = resolveParameter(((PsiInstanceOfExpression)expr).getOperand());
      if (parameter >= 0) {
        return ContainerUtil.map(states, new Function<ValueConstraint[], MethodContract>() {
          @Override
          public MethodContract fun(ValueConstraint[] state) {
            return new MethodContract(withConstraint(state, parameter, NULL_VALUE), FALSE_VALUE);
          }
        });
      }
    }

    final ValueConstraint constraint = getLiteralConstraint(expr);
    if (constraint != null) {
      return toContracts(states, constraint);
    }

    int parameter = resolveParameter(expr);
    if (parameter >= 0) {
      List<MethodContract> result = ContainerUtil.newArrayList();
      for (ValueConstraint[] state : states) {
        if (state[parameter] != ANY_VALUE) {
          // the second 'o' reference in cases like: if (o != null) return o;
          result.add(new MethodContract(state, state[parameter]));
        } else {
          // if (boolValue) ...
          result.add(new MethodContract(withConstraint(state, parameter, TRUE_VALUE), TRUE_VALUE));
          result.add(new MethodContract(withConstraint(state, parameter, FALSE_VALUE), FALSE_VALUE));
        }
      }
      return result;
    }

    return Collections.emptyList();
  }

  private List<MethodContract> visitEqualityComparison(List<ValueConstraint[]> states,
                                                       PsiExpression op1,
                                                       PsiExpression op2,
                                                       boolean equality) {
    int parameter = resolveParameter(op1);
    ValueConstraint constraint = getLiteralConstraint(op2);
    if (parameter < 0 || constraint == null) {
      parameter = resolveParameter(op2);
      constraint = getLiteralConstraint(op1);
    }
    if (parameter >= 0 && constraint != null) {
      List<MethodContract> result = ContainerUtil.newArrayList();
      for (ValueConstraint[] state : states) {
        result.add(new MethodContract(withConstraint(state, parameter, constraint), equality ? TRUE_VALUE : FALSE_VALUE));
        result.add(new MethodContract(withConstraint(state, parameter, negateConstraint(constraint)), equality ? FALSE_VALUE : TRUE_VALUE));
      }
      return result;
    }
    return Collections.emptyList();
  }

  private static List<MethodContract> toContracts(List<ValueConstraint[]> states,
                                                  final ValueConstraint constraint) {
    return ContainerUtil.map(states, new Function<ValueConstraint[], MethodContract>() {
      @Override
      public MethodContract fun(ValueConstraint[] state) {
        return new MethodContract(state, constraint);
      }
    });
  }

  private List<MethodContract> visitLogicalOperation(PsiExpression[] operands, boolean conjunction, List<ValueConstraint[]> states) {
    ValueConstraint breakValue = conjunction ? FALSE_VALUE : TRUE_VALUE;
    List<MethodContract> finalStates = ContainerUtil.newArrayList();
    for (PsiExpression operand : operands) {
      List<MethodContract> opResults = visitExpression(states, operand);
      finalStates.addAll(filterReturning(opResults, breakValue));
      states = antecedentsOf(filterReturning(opResults, negateConstraint(breakValue)));
    }
    finalStates.addAll(toContracts(states, negateConstraint(breakValue)));
    return finalStates;
  }

  private static List<ValueConstraint[]> antecedentsOf(List<MethodContract> values) {
    return ContainerUtil.map(values, new Function<MethodContract, ValueConstraint[]>() {
      @Override
      public ValueConstraint[] fun(MethodContract contract) {
        return contract.arguments;
      }
    });
  }

  private static List<MethodContract> filterReturning(List<MethodContract> values, final ValueConstraint result) {
    return ContainerUtil.filter(values, new Condition<MethodContract>() {
      @Override
      public boolean value(MethodContract contract) {
        return contract.returnValue == result;
      }
    });
  }

  @NotNull
  private List<MethodContract> visitStatements(List<ValueConstraint[]> states, PsiStatement... statements) {
    List<MethodContract> result = ContainerUtil.newArrayList();
    for (PsiStatement statement : statements) {
      if (statement instanceof PsiBlockStatement && ((PsiBlockStatement)statement).getCodeBlock().getStatements().length == 1) {
        result.addAll(visitStatements(states, ((PsiBlockStatement)statement).getCodeBlock().getStatements()));
      }
      else if (statement instanceof PsiIfStatement) {
        List<MethodContract> conditionResults = visitExpression(states, ((PsiIfStatement)statement).getCondition());

        PsiStatement thenBranch = ((PsiIfStatement)statement).getThenBranch();
        if (thenBranch != null) {
          result.addAll(visitStatements(antecedentsOf(filterReturning(conditionResults, TRUE_VALUE)), thenBranch));
        }

        List<ValueConstraint[]> falseStates = antecedentsOf(filterReturning(conditionResults, FALSE_VALUE));
        PsiStatement elseBranch = ((PsiIfStatement)statement).getElseBranch();
        if (elseBranch != null) {
          result.addAll(visitStatements(falseStates, elseBranch));
        } else if (alwaysReturns(thenBranch)) {
          states = falseStates;
          continue;
        }
      }
      else if (statement instanceof PsiThrowStatement) {
        result.addAll(toContracts(states, THROW_EXCEPTION));
      }
      else if (statement instanceof PsiReturnStatement) {
        result.addAll(visitExpression(states, ((PsiReturnStatement)statement).getReturnValue()));
      }
      else if (statement instanceof PsiAssertStatement) {
        List<MethodContract> conditionResults = visitExpression(states, ((PsiAssertStatement)statement).getAssertCondition());
        result.addAll(toContracts(antecedentsOf(filterReturning(conditionResults, FALSE_VALUE)), THROW_EXCEPTION));
      }

      break; // visit only the first statement unless it's 'if' whose 'then' always returns and the next statement is effectively 'else'
    }
    return result;
  }

  private static boolean alwaysReturns(@Nullable PsiStatement statement) {
    if (statement instanceof PsiReturnStatement || statement instanceof PsiThrowStatement) return true;
    if (statement instanceof PsiBlockStatement) {
      for (PsiStatement child : ((PsiBlockStatement)statement).getCodeBlock().getStatements()) {
        if (alwaysReturns(child)) {
          return true;
        }
      }
    }
    if (statement instanceof PsiIfStatement) {
      return alwaysReturns(((PsiIfStatement)statement).getThenBranch()) &&
             alwaysReturns(((PsiIfStatement)statement).getElseBranch());
    }
    return false;
  }

  @Nullable
  private static ValueConstraint getLiteralConstraint(@Nullable PsiExpression expr) {
    if (expr instanceof PsiLiteralExpression) {
      if (expr.textMatches(PsiKeyword.TRUE)) return TRUE_VALUE;
      if (expr.textMatches(PsiKeyword.FALSE)) return FALSE_VALUE;
      if (expr.textMatches(PsiKeyword.NULL)) return NULL_VALUE;
    }
    return null;
  }

  private static ValueConstraint negateConstraint(@NotNull ValueConstraint constraint) {
    //noinspection EnumSwitchStatementWhichMissesCases
    switch (constraint) {
      case NULL_VALUE: return NOT_NULL_VALUE;
      case NOT_NULL_VALUE: return NULL_VALUE;
      case TRUE_VALUE: return FALSE_VALUE;
      case FALSE_VALUE: return TRUE_VALUE;
    }
    return constraint;
  }

  private int resolveParameter(@Nullable PsiExpression expr) {
    if (expr instanceof PsiReferenceExpression && !((PsiReferenceExpression)expr).isQualified()) {
      String name = expr.getText();
      PsiParameter[] parameters = myMethod.getParameterList().getParameters();
      for (int i = 0; i < parameters.length; i++) {
        if (name.equals(parameters[i].getName())) {
          return i;
        }
      }
    }
    return -1;
  }

  private static ValueConstraint[] withConstraint(ValueConstraint[] constraints, int index, ValueConstraint constraint) {
    ValueConstraint[] copy = constraints.clone();
    copy[index] = constraint;
    return copy;
  }

}