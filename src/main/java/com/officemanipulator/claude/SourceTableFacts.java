package com.officemanipulator.claude;

import java.math.BigDecimal;
import java.util.*;

/** Conservative sums for explicitly labelled additive columns in inline CSV tables.
 * Does not infer quantities from prose, sum identifiers, or sum rates/averages. */
final class SourceTableFacts {
    private SourceTableFacts() {}
    static String summarize(String source) {
        if (source == null) return "";
        String[] lines = source.split("\\R");
        StringBuilder result = new StringBuilder();
        for (int i=0; i<lines.length-2; i++) {
            String[] labels=lines[i].split(",",-1);
            if (labels.length<2 || lines[i].strip().matches("^[0-9].*")) continue;
            for(int col=0; col<labels.length; col++) {
                String label=labels[col].strip();
                String lower=label.toLowerCase(Locale.forLanguageTag("tr"));
                if (!lower.matches(".*(katılım|katilim|gider|harcama|toplanan|quantity|expense|attendance).*")) continue;
                if (lower.matches(".*(%|oran|ortalama|rate|average|birim|unit).*")) continue;
                BigDecimal total=BigDecimal.ZERO; int count=0;
                for(int j=i+1;j<lines.length;j++) {
                    String[] cells=lines[j].split(",",-1);
                    if(cells.length<=col || !cells[0].strip().matches("[0-9]+") || !cells[col].strip().matches("-?[0-9]+(?:\\.[0-9]+)?")) break;
                    total=total.add(new BigDecimal(cells[col].strip())); count++;
                }
                if(count>=2) result.append(label).append(" = ").append(total.stripTrailingZeros().toPlainString()).append(" (sum of ").append(count).append(" rows)\n");
            }
        }
        return result.toString();
    }
}
