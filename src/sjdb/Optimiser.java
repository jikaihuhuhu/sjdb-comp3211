package sjdb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

public class Optimiser {
	
	private Catalogue cat;
	
	private static final Estimator est = new Estimator();
	private static Operator top;

	public Optimiser(Catalogue cat) {
		this.cat = cat;
	}

	public Operator optimise(Operator plan) {
		
		top = plan;
	
		Sections s = new Sections();
		plan.accept(s);
		
		System.out.println("Attributes: " + s.allAttributes);
		System.out.println("Predicates: " + s.allPredicates);
		System.out.println("Scans: " + s.allScans);
		
		//Shakibs method
		List<Operator> operationBlocks = firstStage(s.allScans, s.allAttributes, s.allPredicates);
		
		List<Predicate> preds = new ArrayList<>();
		preds.addAll(s.allPredicates);
		
		List<List<Predicate>> permutedPredicates = generatePerm(preds);
		
		
		Operator cheapestPlan = null;
		Integer cheapestCost = Integer.MAX_VALUE;
		
		for (List<Predicate> p : permutedPredicates) {
			
			List<Operator> blocks = new ArrayList<>();
			blocks.addAll(operationBlocks);
			
			Operator aPlan = buildProductOrJoin(blocks, p);
			
			Integer i = est.getCost(aPlan);
			System.out.println("Found plan with cost: " + i);
			
			cheapestPlan = (i < cheapestCost) ? aPlan : cheapestPlan;
			cheapestCost = (i < cheapestCost) ? i : cheapestCost;
		}
		
		System.out.println("Cheapest cost = " + est.getCost(cheapestPlan));
		
//		if (plan instanceof Project)
//			return new Project(cheapestPlan, ((Project) plan).getAttributes());
//		else
			return cheapestPlan;
		
		
		
	
//		if (plan instanceof Project)
//			return new Project(secondStageResult, ((Project) top).getAttributes());
//		else
//			return secondStageResult;
	}

	
	public class tupleCountComparator implements Comparator<Operator> {
	    @Override
	    public int compare(Operator o1, Operator o2) {
	        return Integer.valueOf(o1.output.getTupleCount()).compareTo(Integer.valueOf(o2.output.getTupleCount()));
	    }
	}
	
	private static Operator checkOperatorForAttribute(List<Operator> oList, Attribute attr){
		Iterator<Operator> oIt = oList.iterator();
		while(oIt.hasNext()){
			Operator curOp = oIt.next();
			if (curOp.getOutput().getAttributes().contains(attr)){
				oIt.remove();
				return curOp;
			}
		}
		return null;
	}

	public static Operator buildProductOrJoin(List<Operator> ops, List<Predicate> preds){
		
		Operator result = null;
		
		if (ops.size() == 1){
			result = ops.get(0);
			result.accept(est);
			return result;
		}
		
		Iterator<Predicate> it = preds.iterator();
		while(it.hasNext()){
			Predicate currentPred = it.next();
			Operator left = checkOperatorForAttribute(ops, currentPred.getLeftAttribute());
			Operator right = checkOperatorForAttribute(ops, currentPred.getRightAttribute());
			Operator newResult = null;
			
			if(left == null || right == null){
				newResult = new Select(left != null? left : right, currentPred);
				it.remove();
			}
			if(left != null && right != null){
				newResult = new Join(left, right, currentPred);
				it.remove();
			}
			
			newResult.accept(est);
			//ops.add(newResult);
			Set<Attribute> neededAttrs = getNecessaryAttrs(preds, top);
			if (neededAttrs.size() == newResult.getOutput().getAttributes().size() &&
					newResult.getOutput().getAttributes().containsAll(neededAttrs)){
				ops.add(newResult);
			}else{
				List<Attribute> neededFromNowOn = newResult.getOutput().getAttributes().stream().filter(attr -> neededAttrs.contains(attr)).collect(Collectors.toList());
				if (neededFromNowOn.size() == 0)
					ops.add(newResult);
				else {
					Project tempProj = new Project(newResult, neededFromNowOn);
					tempProj.accept(est);
					ops.add(tempProj);
				}
			}
			
		}
		
		result = ops.get(0);
		
		return ops.get(0);
	}

	public static Operator buildSelectsOnTop(Operator op, Set<Predicate> preds){
		List<Operator> oList = new ArrayList<>();
		oList.add(op);
			
		Iterator<Predicate> it = preds.iterator();
		while(it.hasNext()){
			
			Predicate currentPred = it.next();
			Operator last = oList.get(oList.size()-1);
			
			if(last.getOutput() == null) last.accept(est);
			
			// attr = val
			if (currentPred.equalsValue() && 
				last.getOutput().getAttributes().contains(currentPred.getLeftAttribute())) 
			{
				oList.add(new Select(last, new Predicate(new Attribute(currentPred.getLeftAttribute().getName(),
															   currentPred.getLeftAttribute().getValueCount()),
												 currentPred.getRightValue())));
				it.remove();
			}
				
			if (!currentPred.equalsValue() && 
				last.getOutput().getAttributes().contains(currentPred.getLeftAttribute()) &&
				last.getOutput().getAttributes().contains(currentPred.getRightAttribute()))
			{
				oList.add(new Select(last, new Predicate(new Attribute(currentPred.getLeftAttribute().getName(),
												  			   currentPred.getLeftAttribute().getValueCount()), 
												 new Attribute(currentPred.getRightAttribute().getName(),
														  	   currentPred.getRightAttribute().getValueCount()))));
				it.remove();
			}
		}
		
		return oList.get(oList.size()-1);
	}
	
	public static Operator buildProjectOnTop(Operator op, Set<Attribute> attrs){
		// see which attributes are to be projected and add a project on top of it 
		List<Attribute> applicable = new ArrayList<>();
		
		if(op.getOutput() == null) op.accept(est);
		
		Iterator<Attribute> attrIt = attrs.iterator();
		while(attrIt.hasNext()){
			Attribute attr = attrIt.next();
			if (op.getOutput().getAttributes().contains(attr)){
				applicable.add(attr);
				attrIt.remove();
			}
		}
		
		if (applicable.size() > 0) {
			Operator op2 = new Project(op, applicable);
			op2.accept(est);
			return op2;
		} else {
			return op;
		}
	}
	
	private static Set<Attribute> getNecessaryAttrs(List<Predicate> predicates, Operator top){
		Set<Attribute> attrsNeeded = new HashSet<>();
		Iterator<Predicate> predIt = predicates.iterator();
		while(predIt.hasNext()){
			Predicate currentPred = predIt.next();
			Attribute left = currentPred.getLeftAttribute();
			Attribute right = currentPred.getRightAttribute();
			attrsNeeded.add(left);
			if (right != null) attrsNeeded.add(right);
		}
		if (top instanceof Project) attrsNeeded.addAll(((Project) top).getAttributes());
		return attrsNeeded;
	}
	
	public static List<Operator> firstStage(Set<Scan> scans, Set<Attribute> attrs, Set<Predicate> predicates) {
		List<Operator> ops = new ArrayList<>(scans.size());
		
		for (Scan s: scans){
			Operator o = buildSelectsOnTop(s, predicates);
			ops.add(buildProjectOnTop(o, attrs));
		}
		
		return ops;
	}
	
	private List<List<Predicate>> generatePerm(List<Predicate> original) {
		if (original.size() == 0) { 
			List<List<Predicate>> result = new ArrayList<List<Predicate>>();
		    result.add(new ArrayList<Predicate>());
		    return result;
		}
		Predicate firstElement = original.remove(0);
		List<List<Predicate>> returnValue = new ArrayList<List<Predicate>>();
		List<List<Predicate>> permutations = generatePerm(original);
		for (List<Predicate> smallerPermutated : permutations) {
		    for (int index=0; index <= smallerPermutated.size(); index++) {
		    	List<Predicate> temp = new ArrayList<Predicate>(smallerPermutated);
		    	temp.add(index, firstElement);
		    	returnValue.add(temp);
		    }
		}
		return returnValue;
	}

	class Sections implements PlanVisitor {
		
		private Set<Attribute> allAttributes = new HashSet<>();
		private Set<Predicate> allPredicates = new HashSet<>();
		private Set<Scan> allScans = new HashSet<Scan>();
		

		@Override
		public void visit(Scan op) {
			allScans.add(new Scan((NamedRelation) op.getRelation()));			
		}

		@Override
		public void visit(Project op) {
			for(Attribute attr : op.getAttributes()) {
				allAttributes.add(new Attribute(attr.getName()));
			}
		}

		@Override
		public void visit(Select op) {
			if(op.getPredicate().equalsValue()) {
				allPredicates.add(new Predicate(op.getPredicate().getLeftAttribute(), op.getPredicate().getRightValue()));
				allAttributes.add(new Attribute(op.getPredicate().getLeftAttribute().getName()));
			} else {
				allPredicates.add(new Predicate(op.getPredicate().getLeftAttribute(), op.getPredicate().getRightAttribute()));
				allAttributes.add(new Attribute(op.getPredicate().getLeftAttribute().getName()));
				allAttributes.add(new Attribute(op.getPredicate().getRightAttribute().getName()));
			}			
		}

		@Override
		public void visit(Product op) {}

		@Override
		public void visit(Join op) {}		
	}

}
