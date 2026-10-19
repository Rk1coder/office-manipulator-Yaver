package com.officemanipulator.claude;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SourceTableFactsTest {
 @Test void sumsNumericColumnsWithoutCountingWeeksOrBudgetAsExpenses() {
  String text="""
   Hafta, Katılım, Ayrı toplanan (kg), Gider (TL), Not
   1,24,18,1200,"Tanıtım, ön ölçüm"
   2,30,22,1400,"Atölye: "Doğru kutu""
   3,28,21,1600,Yağmur
   4,36,27,1800,Yurt
   5,40,31,2200,Kütüphane
   6,42,33,2600,Son
   Bütçe 12000 TL.
   """;
  String facts=SourceTableFacts.summarize(text);
  assertTrue(facts.contains("Katılım = 200"));
  assertTrue(facts.contains("Ayrı toplanan (kg) = 152"));
  assertTrue(facts.contains("Gider (TL) = 10800"));
  assertFalse(facts.contains("Hafta ="));
 }
 @Test void doesNotGuessTotalsFromProseOrRates() {
  assertEquals("",SourceTableFacts.summarize("Bütçe 12000 TL, hedef %10.\nSonuç %8."));
  assertEquals("",SourceTableFacts.summarize("Hafta, Oran (%)\n1,20\n2,40"));
 }
}
