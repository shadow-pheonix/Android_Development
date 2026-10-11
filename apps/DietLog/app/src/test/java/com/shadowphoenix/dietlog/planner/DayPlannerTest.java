package com.shadowphoenix.dietlog.planner;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class DayPlannerTest {
    static MealRecipe meal(String id,double kcal,double protein,double price,String tag) {
        PlannerFood f=new PlannerFood(id,id,"cooked",new double[]{kcal,protein,kcal/8,kcal/18,5,50,3,1},new HashSet<>(Arrays.asList(tag)),true,price);
        return new MealRecipe("meal:"+id,id,15,10,false,"Indian","Serve",100,Arrays.asList(new MealRecipe.Ingredient(f,100)));
    }
    @Test public void exclusionCannotBeOverriddenByPerfectMacrosOrHistory() {
        DayPlanner.Request r=new DayPlanner.Request();r.profile.ratings.put("banned",FoodPreference.NEVER);
        DayPlanner.Result result=new DayPlanner().plan(Arrays.asList(meal("banned",500,25,10,"vegetable"),meal("other",450,20,15,"fruit")),r);
        assertEquals(4,result.choices.size());for(DayPlanner.Choice c:result.choices)assertEquals("meal:other",c.recipe.id);
    }
    @Test public void choosesBudgetFeasibleWholeDayAndIncludesConsumedFood() {
        DayPlanner.Request r=new DayPlanner.Request();r.profile.dailyBudget=50;r.consumed=new double[]{500,25,60,15,5,50,3,1};r.remaining[0]=false;
        DayPlanner.Result result=new DayPlanner().plan(Arrays.asList(meal("cheap",500,25,10,"vegetable"),meal("costly",500,40,100,"fruit")),r);
        assertTrue(result.budgetMet);assertTrue(result.cost<=50);assertEquals(3,result.choices.size());assertTrue(result.totals[0]>=500);
    }
    @Test public void lockIsPreservedAndConflictingLockIsExplained() {
        MealRecipe a=meal("a",500,25,10,"dairy");DayPlanner.Request r=new DayPlanner.Request();r.locked.put(1,new DayPlanner.Choice(1,a,1));
        DayPlanner.Result result=new DayPlanner().plan(Arrays.asList(a,meal("b",450,20,10,"vegetable")),r);
        assertSame(a,result.choices.get(1).recipe);assertEquals(1,result.choices.get(1).scale,0);
        r.profile.allergies.add("dairy");result=new DayPlanner().plan(Arrays.asList(a),r);assertTrue(result.choices.isEmpty());assertFalse(result.notes.isEmpty());
    }
    @Test public void impossibleBudgetNeverClaimsSuccessAndSearchIsDeterministic() {
        DayPlanner.Request r=new DayPlanner.Request();r.profile.dailyBudget=1;List<MealRecipe> meals=Arrays.asList(meal("a",500,25,100,"vegetable"));
        DayPlanner.Result a=new DayPlanner().plan(meals,r),b=new DayPlanner().plan(meals,r);assertFalse(a.budgetMet);assertFalse(a.notes.isEmpty());assertEquals(a.cost,b.cost,0);
    }
    @Test public void missingMicrosAreUnknownAndCustomTargetsAreNotChanged() {
        DayPlanner.Request r=new DayPlanner.Request();r.targets[1]=300;double saved=r.targets[0];
        MealRecipe a=MealDataTest.recipe(MealDataTest.food("a","grain",true));
        DayPlanner.Result result=new DayPlanner().plan(Arrays.asList(a),r);assertTrue(Double.isNaN(result.totals[4]));assertEquals(saved,r.targets[0],0);
        assertTrue(result.notes.stream().anyMatch(n->n.contains("conflict")));
    }
    @Test public void alreadyOverTargetStillOffersRemainingMeals() {
        DayPlanner.Request r=new DayPlanner.Request();r.consumed[0]=2400;Arrays.fill(r.remaining,false);r.remaining[3]=true;
        DayPlanner.Result result=new DayPlanner().plan(Arrays.asList(meal("a",500,20,10,"vegetable")),r);assertEquals(1,result.choices.size());assertTrue(result.totals[0]>2400);
    }
}
