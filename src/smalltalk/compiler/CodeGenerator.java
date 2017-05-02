package smalltalk.compiler;

import org.antlr.symtab.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;
import smalltalk.compiler.symbols.*;
import smalltalk.compiler.symbols.STCompiledBlock;
import smalltalk.vm.primitive.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fill STBlock, STMethod objects in Symbol table with bytecode,
 * {@link STCompiledBlock}.
 */
public class CodeGenerator extends SmalltalkBaseVisitor<Code> {
	public static final boolean dumpCode = false;

	public STClass currentClassScope;
	public Scope currentScope;

	/** With which compiler are we generating code? */
	public final Compiler compiler;

	public CodeGenerator(Compiler compiler) {
		this.compiler = compiler;
	}


	public final Map<Scope,StringTable> stringTableMap = new HashMap<>();
	/** This and defaultResult() critical to getting code to bubble up the
	 *  visitor call stack when we don't implement every method.
	 */
	@Override
	protected Code aggregateResult(Code aggregate, Code nextResult) {
		if ( aggregate!=Code.None ) {
			if ( nextResult!=Code.None ) {
				return aggregate.join(nextResult);
			}
			return aggregate;
		}
		else {
			return nextResult;
		}
	}

	@Override
	protected Code defaultResult() {
		return Code.None;
	}

	@Override
	public Code visitFile(SmalltalkParser.FileContext ctx) {
		currentScope = compiler.symtab.GLOBALS;
		visitChildren(ctx);
		return Code.None;
	}

	@Override
	public Code visitClassDef(SmalltalkParser.ClassDefContext ctx) {
		currentClassScope = ctx.scope;
		pushScope(ctx.scope);
		Code code = visitChildren(ctx);
		//code = code.join(visit(ctx.instanceVars()));
		code = code.join(Compiler.pop());
		code = code.join(Compiler.push_self());
		code = code.join(Compiler.method_return());

		// System.out.println("Field: " + ctx.scope.getFields().toArray());

		currentClassScope = (STClass)ctx.scope.getSuperClassScope();
		popScope();
		//currentClassScope = null;
		return code;
	}

//	@Override
//	public Code visitNamedMethod(SmalltalkParser.NamedMethodContext ctx) {
//		currentScope = ctx.scope;
//		pushScope(ctx.scope);
//		String methodName = ctx.getText();
//
//		popScope();
//		currentScope = null;
//
//	}

	/**
	 All expressions have values. Must pop each expression value off, except
	 last one, which is the block return value. Visit method for blocks will
	 issue block_return instruction. Visit method for method will issue
	 pop self return.  If last expression is ^expr, the block_return or
	 pop self return is dead code but it is always there as a failsafe.

	 localVars? expr ('.' expr)* '.'?
	 */


	@Override
	public Code visitBlock(SmalltalkParser.BlockContext ctx) {
		pushScope(ctx.scope);
		STBlock block = ctx.scope;
		Code code = new Code();
		StringTable st = new StringTable();
//		if (compiler.genDbg) {
//			String fullPath = compiler.getFileName();
//			st.add(fullPath.substring(fullPath.lastIndexOf("/") + 1));
//		}
		stringTableMap.put(currentScope, st);
		code = code.join(visitChildren(ctx));
		if(ctx.body() instanceof SmalltalkParser.EmptyBodyContext) {
//			if (compiler.genDbg) {
//				code = Code.join(code, dbgAtEndBlock(ctx.start));
//			}
			code = code.join(Compiler.push_nil());
		}
//		if (compiler.genDbg) {
//			code = Code.join(code, dbgAtEndBlock(ctx.stop));
//		}
		code = code.join(Compiler.block_return());
		//ctx.scope.compiledBlock = getCompiledBlock(ctx.scope, code);
		ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope,block);

		popScope();
		return Code.None;
	}

	@Override
	public Code visitMain(SmalltalkParser.MainContext ctx) {
		if (ctx.scope != null) {
			pushScope(ctx.classScope);
			pushScope(ctx.scope);
			currentClassScope = ctx.classScope;

			Code code = visitChildren(ctx);
			// always add ^self in case no return statement
			// pop final value unless block is empty
			code = code.join(Compiler.pop());
			code = code.join(Compiler.push_self());
			code = code.join(Compiler.method_return());

			STMethod stMethod = currentClassScope.resolveMethod("main");
			System.out.println("enclosingscope:"+ stMethod.getEnclosingScope());
			ctx.scope.compiledBlock = new STCompiledBlock(currentClassScope,stMethod);
			popScope();
			popScope();
			return code;
		}else {
			return Code.None;
		}
	}


	@Override
	public Code visitPrimitiveMethodBlock(SmalltalkParser.PrimitiveMethodBlockContext ctx) {
		SmalltalkParser.MethodContext methodContext = (SmalltalkParser.MethodContext) ctx.getParent();
		pushScope(methodContext.scope);
		Code code = visitChildren(ctx);
		STPrimitiveMethod primitiveMethod = (STPrimitiveMethod) currentScope.resolve(ctx.selector);
		STCompiledBlock compiledBlock = new STCompiledBlock(currentClassScope, primitiveMethod);

		methodContext.scope.compiledBlock = compiledBlock;
		methodContext.scope.compiledBlock.bytecode = code.bytes();

		popScope();
		return code;
	}

	public STCompiledBlock getCompiledPrimitive(STPrimitiveMethod primitive) {
		STCompiledBlock compiledMethod = new STCompiledBlock(currentClassScope, primitive);
		return compiledMethod;
	}


	@Override
	public Code visitFullBody(SmalltalkParser.FullBodyContext ctx) {
		// fill in
		Code code = new Code();
		if (ctx.localVars() != null) {
			code = code.join(visit(ctx.localVars()));
		}
		code = code.join(visit(ctx.stat(0)));
		if(ctx.stat().size() > 1) {
			for (int i = 1; i < (ctx.stat()).size(); i++) {
				code.join(Compiler.pop());
				code.join(visit(ctx.stat(i)));
			}
		}
		return code;
	}


	@Override
	public Code visitAssign(SmalltalkParser.AssignContext ctx) {
		Code store_value = store(ctx.lvalue().ID().getText());
		Code message = visit(ctx.messageExpression());
		Code code = message.join(store_value);
		return code;
	}

	@Override
	public Code visitMessageExpression(SmalltalkParser.MessageExpressionContext ctx) {
		Code code = visit(ctx.keywordExpression());
		return code;
	}

	@Override
	public Code visitPassThrough(SmalltalkParser.PassThroughContext ctx) {
		Code code = visitChildren(ctx);
		return code;
	}

	@Override
	public Code visitBinaryExpression(SmalltalkParser.BinaryExpressionContext ctx) {
		Code code = visit(ctx.unaryExpression(0));
		if(ctx.bop() != null){
			for (int i = 0; i < ctx.bop().size(); i++){
				code.join(visit(ctx.unaryExpression(i + 1)));
				code.join(visit(ctx.bop(i)));
			}
		}
		return code;
	}


	@Override
	public Code visitBop(SmalltalkParser.BopContext ctx) {
		int literalIndex = getLiteralIndex(ctx.getText());
		Code code = Compiler.send(1, literalIndex);
		return code;
	}

	@Override
	public Code visitUnaryIsPrimary(SmalltalkParser.UnaryIsPrimaryContext ctx) {
		Code code = visitChildren(ctx);
		return code;
	}

	@Override
	public Code visitUnaryMsgSend(SmalltalkParser.UnaryMsgSendContext ctx) {
		Code code = visit(ctx.unaryExpression());
		int d = 0;
		code = code.join(Compiler.send(d, getLiteralIndex(ctx.ID().getText())));
		return code;
	}


	@Override
	public Code visitUnarySuperMsgSend(SmalltalkParser.UnarySuperMsgSendContext ctx) {
		Code code = new Code();
		code = code.join(Compiler.push_self());
		if(ctx.getText().contains("super")) {
			code = code.join(Compiler.send_super(0, (short) getLiteralIndex(ctx.ID().toString())));
		}
		return code;
	}

	@Override
	public Code visitPrimary(SmalltalkParser.PrimaryContext ctx) {
		Code code = new Code();
		if(ctx.block() != null){
			String[] split = ctx.block().scope.getName().split("-block");
			String number = split[1];
			code = code.join(Compiler.block(Integer.valueOf(number)));
		}
		code = code.join(visitChildren(ctx));
		return code;
	}

	@Override
	public Code visitId(SmalltalkParser.IdContext ctx) {
		Code code = push(ctx.getText());
		return code;
	}

	@Override
	public Code visitArray(SmalltalkParser.ArrayContext ctx) {
		Code code = visitChildren(ctx);
		code = code.join(Compiler.push_array(ctx.messageExpression().size()));
		return code;
	}

	@Override
	public Code visitLiteral(SmalltalkParser.LiteralContext ctx) {
		Code code = new Code();
		short litIndex;
		if(ctx.NUMBER() != null){
			if(ctx.getText().contains(".")){
				code = Compiler.push_float(Float.parseFloat(ctx.getText()));
			}else {
				code = Compiler.push_int(Integer.parseInt(ctx.getText()));
			}
		}else if(ctx.CHAR() != null){
			litIndex = (short)getLiteralIndex(ctx.getText());
			code = Compiler.push_char(litIndex);
		}else if(ctx.STRING() != null){
			litIndex = (short)getLiteralIndex(ctx.getText());
			code = Compiler.push_literal(litIndex);
		}else if(ctx.getText().equals("nil")){
			code = Compiler.push_nil();
		}else if(ctx.getText().equals("self")){
			code = Compiler.push_self();
		}else if(ctx.getText().equals("true")) {
			code = Compiler.push_true();
		}else if(ctx.getText().equals("false")){
			code = Compiler.push_false();
		}
		return code;
	}

	@Override
	public Code visitKeywordSend(SmalltalkParser.KeywordSendContext ctx) {
		Code code = new Code();
		if(ctx.KEYWORD(0) != null) {
			code = code.join(visit(ctx.recv));
			String keyWord = "";
			for (int i = 0; i < ctx.args.size(); i++){
				code.join(visit(ctx.binaryExpression(i + 1)));
				keyWord = keyWord + ctx.KEYWORD(i).getText();
			}
			code.join(Compiler.send((keyWord.split(":").length), getLiteralIndex(keyWord)));
		} else {
			code = visitChildren(ctx);
		}
		return code;
	}
	@Override
	public Code visitReturn(SmalltalkParser.ReturnContext ctx) {
		Code e = visit(ctx.messageExpression());
//		if ( compiler.genDbg ) {
//			e = Code.join(e, dbg(ctx.start)); // put dbg after expression as that is when it executes
//		}
		Code code = e.join(Compiler.method_return());
		return code;
	}

	@Override
	public Code visitInstanceVars(SmalltalkParser.InstanceVarsContext ctx) {
		if(ctx != null) {
			Code code = new Code();
			for(TerminalNode id: ctx.localVars().ID()) {
				code = push(id.getText());
			}
			return code;
		}else {
			return Code.None;
		}
	}

	@Override
	public Code visitBlockArgs(SmalltalkParser.BlockArgsContext ctx) {
		Code code = new Code();
		for (int i = 0; i < ctx.ID().size(); i++){
			code = code.join(visit(ctx.ID(i)));
		}
		return code;
	}



	public int getLiteralIndex(String s)
	{
		StringTable stable = stringTableMap.get(currentScope);
		if(stable == null){
			stable = new StringTable();
			if (compiler.genDbg) {
				String fullPath = compiler.getFileName();
				stable.add(fullPath.substring(fullPath.lastIndexOf("/") + 1));
			}
		}
		if(s.contains("\'")){
			s = s.replace("\'", "");
		}
		int litIndex = stable.add(s);
		stringTableMap.put(currentScope, stable);
		return litIndex;
		//return 0;
	}

	public Code store(String id) {
		Symbol var = currentScope.resolve(id);
		if(var instanceof STField){
			int i = var.getInsertionOrderNumber();
			return Compiler.store_field(i);
		}else if(var instanceof STVariable || var instanceof STArg){
			int i = var.getInsertionOrderNumber();
			int delta = getDeltaValue(var);		// this is really the delta from current scope to var.scope
			return Compiler.store_local(delta, i);
		}
		return Code.None;
		//return null;
	}

	private int getIndexField(String varName){
		int i = 0;
		Object[] fields = currentClassScope.getFields().toArray();
		for(int j = 0; j < fields.length; j++){
			if (fields[j].toString().contains(varName)){
				i = j;
			}
		}
		return i;
	}

	private int getDeltaValue(Symbol var){
		Scope scope = var.getScope();
		Scope cScope = currentScope;
		int result = 0;
		while (cScope != scope){
			result ++;
			cScope = cScope.getEnclosingScope();
		}
		return result;
	}
	public Code push(String id) {
		Symbol var = currentScope.resolve(id);
		if(var != null) {
			if (var instanceof STField) {
				int i = getIndexField(var.getName());
				return Compiler.push_field(i);
			} else if ((var instanceof STVariable) || (var instanceof STArg)) {
				int i = var.getInsertionOrderNumber();
				int delta = getDeltaValue(var);
				return Compiler.push_local(delta, i);
			} else if (var instanceof STClass){
				return Compiler.push_global(getLiteralIndex(var.getName()));
			}
		} else{
			return Compiler.push_global(getLiteralIndex(id));
		}
		return Code.None;
		//return null;
	}

	public void pushScope(Scope scope) {
		currentScope = scope;
	}

	public void popScope() {
//		if ( currentScope.getEnclosingScope()!=null ) {
//			System.out.println("popping from " + currentScope.getScopeName() + " to " + currentScope.getEnclosingScope().getScopeName());
//		}
//		else {
//			System.out.println("popping from " + currentScope.getScopeName() + " to null");
//		}
		currentScope = currentScope.getEnclosingScope();
	}
	public Code dbgAtEndMain(Token t) {
		int charPos = t.getCharPositionInLine() + t.getText().length();
		return dbg(t.getLine(), charPos);
	}

	public Code dbgAtEndBlock(Token t) {
		int charPos = t.getCharPositionInLine() + t.getText().length();
		charPos -= 1; // point at ']'
		return dbg(t.getLine(), charPos);
	}

	public Code dbg(Token t) {
		return dbg(t.getLine(), t.getCharPositionInLine());
	}

	public Code dbg(int line, int charPos) {
		return Compiler.dbg(getLiteralIndex(compiler.getFileName()), line, charPos);
	}



	public Code sendKeywordMsg(ParserRuleContext receiver,
							   Code receiverCode,
							   List<SmalltalkParser.BinaryExpressionContext> args,
							   List<TerminalNode> keywords)
	{
		return null;
	}

	public String getProgramSourceForSubtree(ParserRuleContext ctx) {
		return null;
	}
}
