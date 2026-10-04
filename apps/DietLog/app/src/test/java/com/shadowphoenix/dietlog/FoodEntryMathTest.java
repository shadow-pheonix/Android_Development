package com.shadowphoenix.dietlog;
import com.shadowphoenix.dietlog.data.FoodEntry;
import org.junit.Test;
import static org.junit.Assert.*;
public final class FoodEntryMathTest {
 @Test public void scalesPer100gValues(){FoodEntry e=FoodEntry.fromPer100g("Rice",250,130,2.7,28,0.3,1L);assertEquals(325,e.calories,0.0001);assertEquals(6.75,e.protein,0.0001);assertEquals(70,e.carbs,0.0001);assertEquals(0.75,e.fat,0.0001);}
 @Test public void preservesCatalogSource(){FoodEntry e=FoodEntry.fromPer100g("Idli",100,140,4,28,1,1L,"INDB","INDB-001");assertEquals("INDB",e.source);assertEquals("INDB-001",e.sourceFoodId);}
}
