/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.timerconditions._ast;

import de.monticore.bpmn.timerconditions.TimerConditionsMill;
import de.monticore.literals.mccommonliterals.MCCommonLiteralsMill;

import static java.time.temporal.ChronoField.DAY_OF_MONTH;
import static java.time.temporal.ChronoField.MONTH_OF_YEAR;
import static java.time.temporal.ChronoField.YEAR;

public class ASTOnDateConditionBuilder extends ASTOnDateConditionBuilderTOP {
  
  public ASTOnDateConditionBuilder setDate(de.monticore.temporal.temporalbasis._ast.ASTDate date) {
    return setDate(TimerConditionsMill.dateBuilder().setYear(MCCommonLiteralsMill
        .natLiteralBuilder().setDigits(String.valueOf(date.get(YEAR))).build()).setMonth(
            MCCommonLiteralsMill.natLiteralBuilder().setDigits(String.valueOf(date.get(
                MONTH_OF_YEAR))).build()).setDay(MCCommonLiteralsMill.natLiteralBuilder().setDigits(
                    String.valueOf(date.get(DAY_OF_MONTH))).build()).build());
  }
  
}
