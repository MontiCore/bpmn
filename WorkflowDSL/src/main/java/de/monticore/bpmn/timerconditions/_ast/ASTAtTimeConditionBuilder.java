/* (c) https://github.com/MontiCore/monticore */
package de.monticore.bpmn.timerconditions._ast;

import de.monticore.bpmn.timerconditions.TimerConditionsMill;
import de.monticore.literals.mccommonliterals.MCCommonLiteralsMill;

import static java.time.temporal.ChronoField.HOUR_OF_DAY;
import static java.time.temporal.ChronoField.MINUTE_OF_HOUR;
import static java.time.temporal.ChronoField.SECOND_OF_MINUTE;

public class ASTAtTimeConditionBuilder extends ASTAtTimeConditionBuilderTOP {
  
  public ASTAtTimeConditionBuilder setTime(de.monticore.temporal.temporalbasis._ast.ASTTime time) {
    ASTTimeBuilder builder = TimerConditionsMill.timeBuilder().setHours(MCCommonLiteralsMill
        .natLiteralBuilder().setDigits(String.valueOf(time.get(HOUR_OF_DAY))).build()).setMinutes(
            MCCommonLiteralsMill.natLiteralBuilder().setDigits(String.valueOf(time.get(
                MINUTE_OF_HOUR))).build());
    if (time.isSupported(SECOND_OF_MINUTE)) {
      builder.setSeconds(MCCommonLiteralsMill.natLiteralBuilder().setDigits(String.valueOf(time.get(
          SECOND_OF_MINUTE))).build());
    }
    return setTime(builder.build());
  }
  
}
