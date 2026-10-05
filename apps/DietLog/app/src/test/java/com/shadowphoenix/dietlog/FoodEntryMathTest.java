package com.shadowphoenix.dietlog;
import com.shadowphoenix.dietlog.data.FoodEntry;
import org.junit.Test;
import static org.junit.Assert.*;
public final class FoodEntryMathTest {
 @Test public void scalesPer100gValues(){FoodEntry e=FoodEntry.fromPer100g("Rice",250,130,2.7,28,0.3,1L);assertEquals(325,e.calories,0.0001);assertEquals(6.75,e.protein,0.0001);assertEquals(70,e.carbs,0.0001);assertEquals(0.75,e.fat,0.0001);}
 @Test public void preservesCatalogSource(){FoodEntry e=FoodEntry.fromPer100g("Idli",100,140,4,28,1,1L,"INDB","INDB-001");assertEquals("INDB",e.source);assertEquals("INDB-001",e.sourceFoodId);}
 @Test public void preservesMealType(){FoodEntry e=FoodEntry.fromPer100g("Egg",100,155,13,1.1,11,1L,FoodEntry.MEAL_BREAKFAST,"IFCT","egg");assertEquals(FoodEntry.MEAL_BREAKFAST,e.mealType);}
 @Test public void normalizesUnknownMealToSnacks(){assertEquals(FoodEntry.MEAL_SNACKS,FoodEntry.normalizeMealType("unknown"));}
 @Test public void mapsHoursToDailyMealSections(){assertEquals(FoodEntry.MEAL_BREAKFAST,FoodEntry.mealForHour(8));assertEquals(FoodEntry.MEAL_LUNCH,FoodEntry.mealForHour(13));assertEquals(FoodEntry.MEAL_SNACKS,FoodEntry.mealForHour(16));assertEquals(FoodEntry.MEAL_DINNER,FoodEntry.mealForHour(20));}
 @Test public void rescaleAmountKeepsNutritionProportional(){FoodEntry e=FoodEntry.fromPer100g("Rice",200,130,2.7,28,0.3,1L,FoodEntry.MEAL_LUNCH);FoodEntry u=e.withAmount(300);assertEquals(300,u.grams,0.0001);assertEquals(e.calories*1.5,u.calories,0.0001);assertEquals(e.protein*1.5,u.protein,0.0001);assertEquals(e.mealType,u.mealType);}
 @Test public void movingMealPreservesNutritionAndAmount(){FoodEntry e=FoodEntry.fromPer100g("Rice",200,130,2.7,28,0.3,1L,FoodEntry.MEAL_LUNCH);FoodEntry u=e.withMealType(FoodEntry.MEAL_DINNER);assertEquals(FoodEntry.MEAL_DINNER,u.mealType);assertEquals(e.grams,u.grams,0.0001);assertEquals(e.calories,u.calories,0.0001);}
 @Test(expected=IllegalArgumentException.class) public void rejectsZeroEditedAmount(){FoodEntry.fromPer100g("Rice",200,130,2.7,28,0.3,1L).withAmount(0);}
}
