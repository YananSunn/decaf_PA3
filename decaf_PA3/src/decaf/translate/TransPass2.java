package decaf.translate;

import java.util.Stack;

import decaf.tree.Tree;
import decaf.tree.Tree.DefaultArray;
import decaf.tree.Tree.ForeachArray;
import decaf.tree.Tree.VarBind;
import decaf.Driver;
import decaf.backend.OffsetCounter;
import decaf.error.DecafError;
import decaf.machdesc.Intrinsic;
import decaf.symbol.Variable;
import decaf.tac.Label;
import decaf.tac.Temp;
import decaf.type.BaseType;
import decaf.type.ClassType;

public class TransPass2 extends Tree.Visitor {

	private Translater tr;

	private Temp currentThis;

	private Stack<Label> loopExits;

	public TransPass2(Translater tr) {
		this.tr = tr;
		loopExits = new Stack<Label>();
	}

	@Override
	public void visitClassDef(Tree.ClassDef classDef) {
		for (Tree f : classDef.fields) {
			f.accept(this);
		}
	}

	@Override
	public void visitMethodDef(Tree.MethodDef funcDefn) {
		if (!funcDefn.statik) {
			currentThis = ((Variable) funcDefn.symbol.getAssociatedScope()
					.lookup("this")).getTemp();
		}
		tr.beginFunc(funcDefn.symbol);
		funcDefn.body.accept(this);
		tr.endFunc();
		currentThis = null;
	}

	@Override
	public void visitTopLevel(Tree.TopLevel program) {
		for (Tree.ClassDef cd : program.classes) {
			cd.accept(this);
		}
	}

	@Override
	public void visitVarDef(Tree.VarDef varDef) {
		if (varDef.symbol.isLocalVar()) {
			Temp t = Temp.createTempI4();
			t.sym = varDef.symbol;
			varDef.symbol.setTemp(t);
		}
	}

	@Override
	public void visitBinary(Tree.Binary expr) {
		expr.left.accept(this);
		expr.right.accept(this);
		switch (expr.tag) {
		case Tree.PLUS:
			expr.val = tr.genAdd(expr.left.val, expr.right.val);
			break;
		case Tree.MINUS:
			expr.val = tr.genSub(expr.left.val, expr.right.val);
			break;
		case Tree.MUL:
			expr.val = tr.genMul(expr.left.val, expr.right.val);
			break;
		case Tree.DIV:
			checkZero(expr.right.val);
			expr.val = tr.genDiv(expr.left.val, expr.right.val);
			break;
		case Tree.MOD:
			checkZero(expr.right.val);
			expr.val = tr.genMod(expr.left.val, expr.right.val);
			break;
		case Tree.AND:
			expr.val = tr.genLAnd(expr.left.val, expr.right.val);
			break;
		case Tree.OR:
			expr.val = tr.genLOr(expr.left.val, expr.right.val);
			break;
		case Tree.LT:
			expr.val = tr.genLes(expr.left.val, expr.right.val);
			break;
		case Tree.LE:
			expr.val = tr.genLeq(expr.left.val, expr.right.val);
			break;
		case Tree.GT:
			expr.val = tr.genGtr(expr.left.val, expr.right.val);
			break;
		case Tree.GE:
			expr.val = tr.genGeq(expr.left.val, expr.right.val);
			break;
		case Tree.EQ:
		case Tree.NE:
			genEquNeq(expr);
			break;
		}
	}

	private void genEquNeq(Tree.Binary expr) {
		if (expr.left.type.equal(BaseType.STRING)
				|| expr.right.type.equal(BaseType.STRING)) {
			tr.genParm(expr.left.val);
			tr.genParm(expr.right.val);
			expr.val = tr.genDirectCall(Intrinsic.STRING_EQUAL.label,
					BaseType.BOOL);
			if(expr.tag == Tree.NE){
				expr.val = tr.genLNot(expr.val);
			}
		} else {
			if(expr.tag == Tree.EQ)
				expr.val = tr.genEqu(expr.left.val, expr.right.val);
			else
				expr.val = tr.genNeq(expr.left.val, expr.right.val);
		}
	}

	@Override
	public void visitAssign(Tree.Assign assign) {
		assign.left.accept(this);
		assign.expr.accept(this);
		switch (assign.left.lvKind) {
		case ARRAY_ELEMENT:
			Tree.Indexed arrayRef = (Tree.Indexed) assign.left;
			Temp esz = tr.genLoadImm4(OffsetCounter.WORD_SIZE);
			Temp t = tr.genMul(arrayRef.index.val, esz);
			Temp base = tr.genAdd(arrayRef.array.val, t);
			tr.genStore(assign.expr.val, base, 0);
			break;
		case MEMBER_VAR:
			Tree.Ident varRef = (Tree.Ident) assign.left;
			tr.genStore(assign.expr.val, varRef.owner.val, varRef.symbol
					.getOffset());
			break;
		case PARAM_VAR:
		case LOCAL_VAR:
			if(assign.left instanceof Tree.Ident) {
				tr.genAssign(((Tree.Ident) assign.left).symbol.getTemp(),
						assign.expr.val);
			}
			else {
				tr.genAssign(((Tree.Var) assign.left).vardef.symbol.getTemp(),
						assign.expr.val);
			}
			break;
		}
	}

	@Override
	public void visitLiteral(Tree.Literal literal) {
		switch (literal.typeTag) {
		case Tree.INT:
			literal.val = tr.genLoadImm4(((Integer)literal.value).intValue());
			break;
		case Tree.BOOL:
			literal.val = tr.genLoadImm4((Boolean)(literal.value) ? 1 : 0);
			break;
		default:
			literal.val = tr.genLoadStrConst((String)literal.value);
		}
	}

	@Override
	public void visitExec(Tree.Exec exec) {
		exec.expr.accept(this);
	}

	@Override
	public void visitUnary(Tree.Unary expr) {
		expr.expr.accept(this);
		switch (expr.tag){
		case Tree.NEG:
			expr.val = tr.genNeg(expr.expr.val);
			break;
		default:
			expr.val = tr.genLNot(expr.expr.val);
		}
	}

	@Override
	public void visitNull(Tree.Null nullExpr) {
		nullExpr.val = tr.genLoadImm4(0);
	}

	@Override
	public void visitBlock(Tree.Block block) {
		for (Tree s : block.block) {
			s.accept(this);
		}
	}

	@Override
	public void visitThisExpr(Tree.ThisExpr thisExpr) {
		thisExpr.val = currentThis;
	}

	@Override
	public void visitReadIntExpr(Tree.ReadIntExpr readIntExpr) {
		readIntExpr.val = tr.genIntrinsicCall(Intrinsic.READ_INT);
	}

	@Override
	public void visitReadLineExpr(Tree.ReadLineExpr readStringExpr) {
		readStringExpr.val = tr.genIntrinsicCall(Intrinsic.READ_LINE);
	}

	@Override
	public void visitReturn(Tree.Return returnStmt) {
		if (returnStmt.expr != null) {
			returnStmt.expr.accept(this);
			tr.genReturn(returnStmt.expr.val);
		} else {
			tr.genReturn(null);
		}

	}

	@Override
	public void visitPrint(Tree.Print printStmt) {
		for (Tree.Expr r : printStmt.exprs) {
			r.accept(this);
			tr.genParm(r.val);
			if (r.type.equal(BaseType.BOOL)) {
				tr.genIntrinsicCall(Intrinsic.PRINT_BOOL);
			} else if (r.type.equal(BaseType.INT)) {
				tr.genIntrinsicCall(Intrinsic.PRINT_INT);
			} else if (r.type.equal(BaseType.STRING)) {
				tr.genIntrinsicCall(Intrinsic.PRINT_STRING);
			}
		}
	}

	@Override
	public void visitIndexed(Tree.Indexed indexed) {
		indexed.array.accept(this);
		indexed.index.accept(this);
		tr.genCheckArrayIndex(indexed.array.val, indexed.index.val);
		
		Temp esz = tr.genLoadImm4(OffsetCounter.WORD_SIZE);
		Temp t = tr.genMul(indexed.index.val, esz);
		Temp base = tr.genAdd(indexed.array.val, t);
		indexed.val = tr.genLoad(base, 0);
	}

	@Override
	public void visitIdent(Tree.Ident ident) {
		if(ident.lvKind == Tree.LValue.Kind.MEMBER_VAR){
			ident.owner.accept(this);
		}
		
		switch (ident.lvKind) {
		case MEMBER_VAR:
			ident.val = tr.genLoad(ident.owner.val, ident.symbol.getOffset());
			break;
		default:
			ident.val = ident.symbol.getTemp();
			break;
		}
	}
	
	@Override
	public void visitBreak(Tree.Break breakStmt) {
		tr.genBranch(loopExits.peek());
	}

	@Override
	public void visitCallExpr(Tree.CallExpr callExpr) {
		if (callExpr.isArrayLength) {
			callExpr.receiver.accept(this);
			callExpr.val = tr.genLoad(callExpr.receiver.val,
					-OffsetCounter.WORD_SIZE);
		} else {
			if (callExpr.receiver != null) {
				callExpr.receiver.accept(this);
			}
			for (Tree.Expr expr : callExpr.actuals) {
				expr.accept(this);
			}
			if (callExpr.receiver != null) {
				tr.genParm(callExpr.receiver.val);
			}
			for (Tree.Expr expr : callExpr.actuals) {
				tr.genParm(expr.val);
			}
			if (callExpr.receiver == null) {
				callExpr.val = tr.genDirectCall(
						callExpr.symbol.getFuncty().label, callExpr.symbol
								.getReturnType());
			} else {
				Temp vt = tr.genLoad(callExpr.receiver.val, 0);
				Temp func = tr.genLoad(vt, callExpr.symbol.getOffset());
				callExpr.val = tr.genIndirectCall(func, callExpr.symbol
						.getReturnType());
			}
		}

	}

	@Override
	public void visitForLoop(Tree.ForLoop forLoop) {
		if (forLoop.init != null) {
			forLoop.init.accept(this);
		}
		Label cond = Label.createLabel();
		Label loop = Label.createLabel();
		tr.genBranch(cond);
		tr.genMark(loop);
		if (forLoop.update != null) {
			forLoop.update.accept(this);
		}
		tr.genMark(cond);
		forLoop.condition.accept(this);
		Label exit = Label.createLabel();
		tr.genBeqz(forLoop.condition.val, exit);
		loopExits.push(exit);
		if (forLoop.loopBody != null) {
			forLoop.loopBody.accept(this);
		}
		tr.genBranch(loop);
		loopExits.pop();
		tr.genMark(exit);
	}

	@Override
	public void visitIf(Tree.If ifStmt) {
		ifStmt.condition.accept(this);
		if (ifStmt.falseBranch != null) {
			Label falseLabel = Label.createLabel();
			tr.genBeqz(ifStmt.condition.val, falseLabel);
			ifStmt.trueBranch.accept(this);
			Label exit = Label.createLabel();
			tr.genBranch(exit);
			tr.genMark(falseLabel);
			ifStmt.falseBranch.accept(this);
			tr.genMark(exit);
		} else if (ifStmt.trueBranch != null) {
			Label exit = Label.createLabel();
			tr.genBeqz(ifStmt.condition.val, exit);
			if (ifStmt.trueBranch != null) {
				ifStmt.trueBranch.accept(this);
			}
			tr.genMark(exit);
		}
	}

	@Override
	public void visitNewArray(Tree.NewArray newArray) {
		newArray.length.accept(this);
		newArray.val = tr.genNewArray(newArray.length.val);
	}

	@Override
	public void visitNewClass(Tree.NewClass newClass) {
		newClass.val = tr.genDirectCall(newClass.symbol.getNewFuncLabel(),
				BaseType.INT);
	}

	@Override
	public void visitWhileLoop(Tree.WhileLoop whileLoop) {
		Label loop = Label.createLabel();
		tr.genMark(loop);
		whileLoop.condition.accept(this);
		Label exit = Label.createLabel();
		tr.genBeqz(whileLoop.condition.val, exit);
		loopExits.push(exit);
		if (whileLoop.loopBody != null) {
			whileLoop.loopBody.accept(this);
		}
		tr.genBranch(loop);
		loopExits.pop();
		tr.genMark(exit);
	}

	@Override
	public void visitTypeTest(Tree.TypeTest typeTest) {
		typeTest.instance.accept(this);
		typeTest.val = tr.genInstanceof(typeTest.instance.val,
				typeTest.symbol);
	}

	@Override
	public void visitTypeCast(Tree.TypeCast typeCast) {
		typeCast.expr.accept(this);
		if (!typeCast.expr.type.compatible(typeCast.symbol.getType())) {
			tr.genClassCast(typeCast.expr.val, typeCast.symbol);
		}
		typeCast.val = typeCast.expr.val;
	}
	
	public void visitSCopyExpr(Tree.SCopyExpr scopy) {
		scopy.expr.accept(this);
        int n = ((ClassType) scopy.expr.type).getSymbol().getSize();
        Temp size = tr.genLoadImm4(n);
        tr.genParm(size);
        Temp result = tr.genIntrinsicCall(Intrinsic.ALLOCATE);
        int time = n / 4 - 1;
        for (int i = 0; i < time; i++)
        {
            Temp tmp = tr.genLoad(scopy.expr.val, (i + 1) * 4);
            tr.genStore(tmp, result, (i + 1) * 4);
        }
        tr.genStore(tr.genLoadVTable(((ClassType) scopy.expr.type).getSymbol().getVtable()), result, 0);
        scopy.sym.setTemp(result);
	}
	
	public void visitGuarded(Tree.Guarded guarded) {
		Label start = Label.createLabel();
        Label[] labels = new Label[guarded.subStmt.size() + 1];
        for (int i = 0; i <= guarded.subStmt.size(); i++)
        {
            labels[i] = Label.createLabel();
        }
        Label last = labels[guarded.subStmt.size()];
        Label end = Label.createLabel();

        tr.genMark(start);
        int k = 0;
        for (Tree stmt : guarded.subStmt)
        {
            ((Tree.IfSubStmt)stmt).expr.accept(this);
            tr.genBeqz(((Tree.IfSubStmt) stmt).expr.val, labels[k]);
            stmt.accept(this);
            tr.genMark(labels[k]);
            k++;
        }
        tr.genMark(last);
        ((Tree.IfSubStmt)guarded.last).expr.accept(this);
        tr.genBeqz(((Tree.IfSubStmt)guarded.last).expr.val, end);
        guarded.last.accept(this);
        tr.genMark(end);
	}
	
	public void visitIfSubStmt(Tree.IfSubStmt ifSubStmt)
    {
        ifSubStmt.stmt.accept(this);
    }
	
	public void visitVar(Tree.Var var)
    {
		Temp t = Temp.createTempI4();
		t.sym = var.vardef.symbol;
		var.vardef.symbol.setTemp(t);
    }
	
	public void visitNewSameArray(Tree.NewSameArray newSameArray) {
		newSameArray.expr.accept(this);
		newSameArray.newsamearray.accept(this);
		int n = 0;
		if(newSameArray.expr.type instanceof ClassType) {
			n = (((ClassType) newSameArray.expr.type).getSymbol().getSize()) - 4;
		}
		else {
			if((BaseType) newSameArray.expr.type == BaseType.BOOL) {
				n = 4;
			}
			else if((BaseType) newSameArray.expr.type == BaseType.INT) {
				n = 4;
			}
			else if((BaseType) newSameArray.expr.type == BaseType.STRING) {
				n = 4;
			}
		}

		Temp result;
		if(newSameArray.expr.type instanceof ClassType) {
			result = tr.genNewSameArray(newSameArray.expr.val, newSameArray.newsamearray.val, n, newSameArray, true);
		}
		else {
			result = tr.genNewSameArray(newSameArray.expr.val, newSameArray.newsamearray.val, n, newSameArray, false);
		}
//		Temp tmp = tr.genLoadImm4(4);
//		result = tr.genAdd(result, tmp);
		newSameArray.val = result;
	}
	
	public void visitDefaultArray(DefaultArray defaultArray) {
		defaultArray.expr2.accept(this);
		defaultArray.expr1.accept(this);
		defaultArray.expr3.accept(this);
		
		
		Temp length = tr.genLoad(defaultArray.expr1.val, -OffsetCounter.WORD_SIZE);
//		Temp length = tr.genLoad(defaultArray.expr1.val, 0);
//		Temp length = tr.genLoadImm4(2);
		Temp cond = tr.genLes(defaultArray.expr2.val, length);
		Temp result = Temp.createTempI4();
//		Temp result = tr.genIntrinsicCall(Intrinsic.ALLOCATE);
		Label err = Label.createLabel();
		tr.genBeqz(cond, err);
		cond = tr.genLes(defaultArray.expr2.val, tr.genLoadImm4(0));
		Label exit = Label.createLabel();
		Label end = Label.createLabel();
		tr.genBeqz(cond, exit);

		tr.genMark(err);
//		tr.genStore(defaultArray.expr3.val, result, 0);
		tr.genAssign(result, defaultArray.expr3.val);
		tr.genBranch(end);
		
		tr.genMark(exit);
		Temp esz = tr.genLoadImm4(OffsetCounter.WORD_SIZE);
		Temp t = tr.genMul(defaultArray.expr2.val, esz);
		Temp base = tr.genAdd(defaultArray.expr1.val, t);		
		Temp tmp = tr.genLoad(base, 0);
//		tr.genStore(tmp, result, 0);
		tr.genAssign(result, tmp);
		tr.genMark(end);
		
		
//		Temp result = tr.genCheckDefaultArrayIndex(defaultArray.expr1.val, defaultArray.expr2.val, defaultArray.expr3.val);
		defaultArray.val = result;
	}
	
	public void visitVarBind(VarBind varbind) {
		Temp t = Temp.createTempI4();
		t.sym = varbind.sym;
		varbind.sym.setTemp(t);
	}
	
	public void visitForeachArray(ForeachArray foreachArray) {
		foreachArray.varbind.accept(this);
		foreachArray.expr1.accept(this);
		
		Label cond = Label.createLabel();
		Label loop = Label.createLabel();
		Label exit = Label.createLabel();

		Temp zero = tr.genLoadImm4(0);
		Temp one = tr.genLoadImm4(1);
		Temp four = tr.genLoadImm4(4);
		Temp element = tr.genAdd(foreachArray.expr1.val, zero);
		Temp length = tr.genLoad(foreachArray.expr1.val, -OffsetCounter.WORD_SIZE);
		Temp index = tr.genLoadImm4(0);
		Temp indextarget = tr.genLoadImm4(0);
		tr.genBranch(cond);
		tr.genMark(loop);
		tr.genMark(cond);
		Temp condtion = tr.genLes(index, length);
		Temp centtmp = tr.genAdd(index, one);
		tr.genAssign(index, centtmp);	
		
		tr.genBeqz(condtion, exit);
		tr.genAssign(foreachArray.varbind.sym.getTemp(), tr.genLoad(element, 0));
		if(foreachArray.expr2 != null) {
			foreachArray.expr2.accept(this);
			tr.genBeqz(foreachArray.expr2.val, exit);
		}

		indextarget = tr.genAdd(element, four);
		tr.genAssign(element, indextarget);
		
		loopExits.push(exit);
		foreachArray.stmt.accept(this);
		tr.genBranch(loop);
		loopExits.pop();		
		tr.genMark(exit);	
	}
	
	private void checkZero(Temp src){
        Temp msg = tr.genLoadStrConst("Decaf runtime error: Division by zero error.");
        Label goon = Label.createLabel();
        Temp cond = tr.genEqu(src, tr.genLoadImm4(0));
        tr.genBeqz(cond, goon);
        tr.genParm(msg);
        tr.genIntrinsicCall(Intrinsic.PRINT_STRING);
        tr.genIntrinsicCall(Intrinsic.HALT);
        tr.genMark(goon);
	}
	 
	private void issueError(DecafError error) {
		Driver.getDriver().issueError(error);
	}
}
