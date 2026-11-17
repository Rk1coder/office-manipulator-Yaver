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
                if (!lower.matches(".*(katılım|katilim|gider|harcama|toplanan|stok|adet|miktar|bütçe|tutar|quantity|expense|attendance|stock|amount|budget).*")) continue;
                if (lower.matches(".*(%|oran|ortalama|rate|average|birim|unit).*")) continue;
                int groupCol=-1; // a category column: its sums per value are what "by category" reports need
                for(int g=0; g<labels.length; g++) if(labels[g].strip().toLowerCase(Locale.forLanguageTag("tr")).matches(".*(kategori|category|bölge|region|departman|department).*")) { groupCol=g; break; }
                BigDecimal total=BigDecimal.ZERO; int count=0;
                Map<String,BigDecimal> groups=new LinkedHashMap<>();
                for(int j=i+1;j<lines.length;j++) {
                    String[] cells=lines[j].split(",",-1);
                    // shortcut: a quoted comma before this column shifts it; the numeric check then usually stops the sum early.
                    if(cells.length<labels.length || !cells[col].strip().matches("-?[0-9]+(?:\\.[0-9]+)?")) break;
                    BigDecimal v=new BigDecimal(cells[col].strip());
                    total=total.add(v); count++;
                    if(groupCol>=0) groups.merge(cells[groupCol].strip(), v, BigDecimal::add);
                }
                if(count>=2) {
                    result.append(label).append(" = ").append(total.stripTrailingZeros().toPlainString()).append(" (sum of ").append(count).append(" rows)\n");
                    if(groups.size()>=2 && groups.size()<count) {
                        result.append(label).append(" by ").append(labels[groupCol].strip()).append(": ");
                        groups.forEach((k,v)->result.append(k).append(" = ").append(v.stripTrailingZeros().toPlainString()).append("; "));
                        result.setLength(result.length()-2); result.append("\n");
                    }
                }
            }
        }
        return result.toString();
    }
}
