package org.ibex.classgen;
import java.io.*;
import java.util.*;

/**
 *  a highly streamlined SSA-form intermediate representation of a
 *  sequence of JVM instructions; all stack manipulation is factored
 *  out.
 */
public class JSSA extends MethodGen implements CGConst {

    // Constructor //////////////////////////////////////////////////////////////////////////////
    
    public JSSA(Type.Class c, DataInput in, ConstantPool cp) throws IOException {
        super(c, in, cp);
        sp = 0;
        stacks = new Phi[size()][];
        locals = new Phi[size()][];
        for(int i=0; i<size(); i++) {
            int n = 0;
            locals[i] = new Phi[maxLocals];
            for(int j=0; j<locals[i].length; j++) locals[i][j] = new Phi();
            if (i==0) {
                if (!isStatic()) locals[i][n++].merge(new Argument("this",method.getDeclaringClass()));
                for(int j=0;j<this.method.getNumArgs(); j++) locals[i][n++].merge(new Argument("arg"+j,this.method.getArgType(j)));
            }
        }

        stack = new Phi[maxStack];
        branchTo(0);
        for(pc=0; pc<size(); pc++) {
            int    op  = get(pc);
            Object arg = getArg(pc);
            try {
                Object o = addOp(op, arg);
                if (o != null) {
                    ops[numOps] = o;
                    ofs[numOps++] = pc;
                }
                if (o!=null && o instanceof Branch) ((Branch)o).branchTo();
                if (o==null || (!(o instanceof Branch))) branchTo(pc+1);
            } catch(RuntimeException e) {
                System.err.println("Had a problem at PC: " + pc + " of " + method);
                e.printStackTrace();
                throw new IOException("invalid class file");
            }
        }
    }

    public void branchTo(int newPC) {
        System.out.println("!!!branchTo("+newPC+")!!!");
        if (stacks[newPC] == null) {
            stacks[newPC] = new Phi[sp];
            for(int i=0; i<sp; i++) stacks[newPC][i] = new Phi();
        }
        if (stacks[newPC].length != sp)
            throw new IllegalArgumentException("stack depth disagreement: " + sp + " " + stacks[newPC].length);
        for(int i=0; i<stacks[newPC].length; i++) stacks[newPC][i].merge(stack[i]);
        for(int i=0; i<maxLocals; i++) locals[newPC][i].merge(locals[pc][i]);
    }
    
    private Object[] ops = new Object[65535];
    private int[] ofs = new int[65535];
    private int numOps = 0;

    // Instance Data; used ONLY during constructor; then thrown away /////////////////////////////////////////////////

    /** this models the JVM locals; it is only used for unwinding stack-ops into an SSA-tree, then thrown away */
    private final Phi[][] locals;
    
    /** this models the JVM stack; it is only used for unwinding stack-ops into an SSA-tree, then thrown away */
    private final Phi[][] stacks;
    private final Phi[] stack;

    /** JVM stack pointer */
    private int pc = 0;
    private int sp = 0;

    private void push(Expr e) {
        if (e.getType() == Type.VOID) throw new IllegalArgumentException("can't push a void");
        if (stack[sp] == null) stack[sp] = new Phi();
        stack[sp++].merge(e);
    }
    private Expr pop() {
        Expr ret = stack[sp-1];
        stack[sp-1] = null;
        sp--;
        return ret;
    }
    
    private Op seqPush(Expr e) {
        push(e);
        return new Seq(e);
    }


    // SSA-node classes /////////////////////////////////////////////////////////////////////////////////////////

    public final Expr VOID_EXPR = new Expr() {
        public Type getType() { return Type.VOID; }
    };
    
    /** an purely imperative operation which does not generate data */
    public abstract class Op {
        //public abstract Op[] predecessors();  // not implemented yet
        //public abstract Op[] successors();    // not implemented yet
        public String toString() { return name(); }
        String name() {
            String name = this.getClass().getName();
            if (name.indexOf('$') != -1) name = name.substring(name.lastIndexOf('$')+1);
            if (name.indexOf('.') != -1) name = name.substring(name.lastIndexOf('.')+1);
            return name;
        }
    }
    
    /** A sequence point. expr is evaluated for side effects at this point, this does not generate data 
        Expressions that haven't been evaluated with Seq are evaluated when they are first encountered
      */
    public class Seq extends Op {
        private final Expr expr;
        public String toString() { return expr.toString(); }
        public Seq(Expr expr) { this.expr = expr; }
    }
    
    /** an operation which generates data */
    public abstract class Expr extends Op {
        //public abstract Expr[] contributors();  // not implemented yet
        //public abstract Expr[] dependents();    // not implemented yet

        /** every JSSA.Expr either remembers its type _OR_ knows how to figure it out (the latter is preferred to eliminate
         *  redundant information that could possibly "disagree" with itself -- this happened a LOT in Soot) */
        public abstract Type getType();
        public String _toString() { return super.toString(); }
        public String toString() { return _toString(); } /*
            String s = (String)bindingMap.get(this);
            if (s != null) return s;
            String prefix;
            if (getType() == Type.VOID) return _toString();
            else if (getType() == Type.DOUBLE || getType() == Type.FLOAT) prefix = "f";
            else if (getType().isPrimitive()) prefix = "i";
            else if (getType().isArray()) prefix = "a";
            else prefix = "o";
            s = prefix + (nextVar++);
            bindingMap.put(this,s);
            return "(" + s + " = " + _toString() + ")";
            }*/
    }

    /**
     *  A "nondeterministic merge" -- for example when the first
     *  instruction in a loop reads from a local which could have been
     *  written to either by some instruction at the end of the
     *  previous iteration of the loop or by some instruction before
     *  the loop (on the first iteration).
     *
     *  Note that Phi's are *mutable*.  This means that when one Phi
     *  holds a reference to another, updates to the referenced Phi
     *  are "seen" by the other Phi.  This prevents us from having to
     *  repeat loops to propagate the Phis.
     */
    public class Phi extends Expr {
        Expr[] inputs;
        public Phi(Expr[] inputs) { this.inputs = inputs; }
        public Phi() { this.inputs = new Expr[0]; }
        public Phi(Expr e1) {
            this.inputs = new Expr[1];
            inputs[0] = e1;
        }
        public Phi(Expr e1, Expr e2) {
            this.inputs = new Expr[2];
            inputs[0] = e1;
            inputs[1] = e2;
        }
        public void merge(Expr e) {
            if (e==this) return;
            for(int i=0; i<inputs.length; i++) if (inputs[i]==e) return;
            Expr[] newinputs = new Expr[inputs.length + 1];
            System.arraycopy(inputs, 0, newinputs, 0, inputs.length);
            newinputs[newinputs.length-1] = e;
            inputs = newinputs;
        }
        public String toString() {
            if (inputs.length == 1) return inputs[0].toString();
            StringBuffer ret = new StringBuffer();
            int count = 0;
            for(int i=0; i<inputs.length; i++) {
                String s = inputs[i].toString().trim();
                if (s.length() == 0) continue;
                count++;
                ret.append(s);
                ret.append(" ");
            }
            if (count == 0) return "";
            if (count == 1) return ret.toString().trim();
            return "{{ " + ret.toString() + "}}";
            //return "{"+ret+"}";
        }
        public Type getType() {
            if (inputs.length == 0) return null;
            // sanity check
            Type t = inputs[0].getType();

            // FIXME: actually this should check type-unifiability... fe, the "type of null" unifies with any Type.Ref
            for(int i=1; i<inputs.length; i++) {
                if (t==null) { t = inputs[i].getType(); continue; }
                if (inputs[i].getType() == null) continue;
                if (inputs[i].getType() != t)
                    throw new Error("Phi node with disagreeing types: " + t + " " + inputs[i].getType() +"\n  Crisis!");
            }
            return t;
        }
    }

    public Phi phi(Expr e) { return e instanceof Phi ? ((Phi)e) : new Phi(e); }

    public class Argument extends Expr {
        public final String name;
        public final Type t;
        public Argument(String name, Type t) { this.name = name; this.t = t; }
        public String _toString() { return name; }
        public Type getType() { return t; }
    }
    

    // Unary Operations //////////////////////////////////////////////////////////////////////////////
    
    public class Not extends Expr {
        public final Expr e;
        public Not(Expr e) {
            if (e.getType() != Type.BOOLEAN) throw new IllegalArgumentException("not needs a boolean expression");
            this.e = e;
        }
        public Type getType() { return Type.BOOLEAN; }
        public String _toString() { return "!(" + e + ")"; }
    }
    
    public class Neg extends Expr {
        public final Expr e;
        public Neg(Expr e) {
            if (!e.getType().isPrimitive()) throw new IllegalArgumentException("can only negate a primitive");
            this.e = e;
        }
        public Type getType() { return e.getType(); }
        public String _toString() { return "- (" + e + ")"; }
    }

    
    // Binary Operations //////////////////////////////////////////////////////////////////////////////

    public abstract class BinExpr extends Expr {
        public final Expr e1;
        public final Expr e2;
        private final String show;
        public BinExpr(Expr e1, Expr e2, String show) { this.e1 = e1; this.e2 = e2; this.show = show; }
        public String _toString() {
            // FEATURE: should we be doing some precedence stuff here? probably not worth it for debugging output
            return "(" + e1 + show + e2 + ")";
        }
    }

    public class Comparison extends BinExpr {
        public Comparison(Expr e1, Expr e2, String show) { super(e1, e2, show); }
        public Type getType() { return Type.BOOLEAN; }
    }

    public class Eq extends Comparison {
        public Eq(Expr e1, Expr e2) {
            super(e1, e2, "=="); 
            if (e1.getType().isPrimitive() != e2.getType().isPrimitive())
                throw new IllegalArgumentException("type mismatch: " + e1.getType() + " and " + e2.getType());
            if (e1.getType().isPrimitive() && e1.getType() != e2.getType())
                throw new IllegalArgumentException("type mismatch: " + e1.getType() + " and " + e2.getType());
            // FEATURE: Check if we can compare these classes
        }
    }
    

    public class PrimitiveComparison extends Comparison {
        public PrimitiveComparison(Expr e1, Expr e2, String show) {
            super(e1, e2, show);
            if (!e1.getType().isPrimitive() || e1.getType() != e2.getType()) throw new IllegalArgumentException("type mismatch");
        }
    }
    
    public class Gt extends PrimitiveComparison { public Gt(Expr e1, Expr e2) { super(e1, e2, ">"); } }
    public class Lt extends PrimitiveComparison { public Lt(Expr e1, Expr e2) { super(e1, e2, "<"); } }
    public class Ge extends PrimitiveComparison { public Ge(Expr e1, Expr e2) { super(e1, e2, ">="); } }
    public class Le extends PrimitiveComparison { public Le(Expr e1, Expr e2) { super(e1, e2, "<="); } }

    

    // Math Operations //////////////////////////////////////////////////////////////////////////////

    public class BinMath extends BinExpr {
        public BinMath(Expr e1, Expr e2, String show) {
            super(e2, e1, show); 
            if (e1.getType() != null && e2.getType() != null && e1.getType() != e2.getType())
                throw new IllegalArgumentException("types disagree");
        }
        public Type getType() { return e1.getType(); }
    }
    
    public class Add  extends BinMath { public  Add(Expr e, Expr e2) { super(e, e2, "+"); } }
    public class Sub  extends BinMath { public  Sub(Expr e, Expr e2) { super(e, e2, "-"); } }
    public class Mul  extends BinMath { public  Mul(Expr e, Expr e2) { super(e, e2, "*"); } }
    public class Rem  extends BinMath { public  Rem(Expr e, Expr e2) { super(e, e2, "%"); } }
    public class Div  extends BinMath { public  Div(Expr e, Expr e2) { super(e, e2, "/"); } }
    public class And  extends BinMath { public  And(Expr e, Expr e2) { super(e, e2, "&"); } }
    public class Or   extends BinMath { public   Or(Expr e, Expr e2) { super(e, e2, "|"); } }
    public class Xor  extends BinMath { public  Xor(Expr e, Expr e2) { super(e, e2, "^"); } }
    
    public class BitShiftExpr extends BinExpr {
        public BitShiftExpr(Expr e1, Expr e2, String show) {
            super(e1,e2,show);
            Type t = e1.getType();
            if (t != Type.INT && t != Type.LONG) throw new IllegalArgumentException("type mismatch");
            if (e2.getType() != Type.INT) throw new IllegalArgumentException("type mismatch");
        }
        public Type getType() { return e1.getType(); }
    }
    public class Shl  extends BitShiftExpr { public  Shl(Expr e, Expr e2) { super(e, e2, "<<"); } }
    public class Shr  extends BitShiftExpr { public  Shr(Expr e, Expr e2) { super(e, e2, ">>"); } }
    public class Ushr extends BitShiftExpr { public Ushr(Expr e, Expr e2) { super(e, e2, ">>>"); } }


    // Other operations //////////////////////////////////////////////////////////////////////////////

    public class Cast extends Expr {
        final Expr e;
        final Type t;
        public Cast(Expr e, Type t) {
            if (e.getType().isRef() != t.isRef()) throw new IllegalArgumentException("invalid cast");
            // FEATURE: Check that one is a subclass of the other if it is a ref
            this.e = e;
            this.t = t; 
        }
        public Type getType() { return t; }
    }

    public class InstanceOf extends Expr {
        final Expr e;
        final Type.Ref t;
        public InstanceOf(Expr e, Type.Ref t) {
            if (!e.getType().isRef()) throw new IllegalArgumentException("can't do an instanceof check on a non-ref");
            this.e = e; 
            this.t = t; 
        }
        public Type getType() { return Type.BOOLEAN; }
    }

    public class Branch extends Op {
        Expr destination = null;
        public Branch(Expr destination) { this.destination = destination; }
        public Branch(MethodGen.Switch s) { /* FIXME */ }
        public Branch() { }
        public void branchTo() { if (destination != null) branchTo(destination); }
        private void branchTo(Expr e) {
            if (e instanceof Phi) {
                Phi phi = (Phi)e;
                for(int i=0; i<phi.inputs.length; i++) branchTo(phi.inputs[i]);
            } else if (e instanceof Label) {
                JSSA.this.branchTo(((Label)e).pc);
            } else {
                throw new IllegalArgumentException("can't branch to a " + e.getClass());
            }
        }
    }
    public class Throw extends Branch {
        public final Expr e;
        public Throw(Expr e) {
            if (!e.getType().isRef()) throw new IllegalArgumentException("can't throw a non ref");
            // FEATURE: CHeck that it is a subclass of Throwable
            this.e = e; 
        }
    }
    public class Return extends Branch {
        final Expr e;
        public Return() { this(VOID_EXPR); }
        public Return(Expr e) {
            this.e = e; 
            if (Type.unify(method.getReturnType(),e.getType()) != method.getReturnType())
               throw new IllegalArgumentException("type mismatch");
        }
        public String toString() { return e.getType() == Type.VOID ? "return" : ("return "+e.toString()); }
    }
    public class Goto extends Branch {
        public Goto(Expr destination) { super(destination); }
        public String toString() { return "goto " + destination; }
    }
    public class RET extends Branch {
        public RET(Expr destination) { super(destination); }
        public String toString() { return "retsub [" + destination + "]"; }
    }
    public class JSR extends Branch {
        public JSR(Expr destination) { super(destination); }
        public String toString() { return "callsub " + destination; }
    }
    public class If extends Branch {
        Expr condition = null;
        public If(Expr condition, Expr destination) { super(destination); this.condition = condition; }
        public String toString() { return "if (" + condition + ") goto " + destination; }
        public void branchTo() {
            if (condition != null) JSSA.this.branchTo(pc+1);
            super.branchTo();
        }
    }

    /** represents a "returnaddr" pushed onto the stack */
    public class Label extends Expr {
        public int pc;
        public Label(int i) { this.pc = i; }
        public Type getType() { throw new Error("attempted to call getType() on a Label"); }
        public String toString() { return "<<label " + pc + ">>"; }
    }

    public class New extends Expr {
        public final Type.Class t;
        public Type getType() { return t; }
        public New(Type.Class t) { this.t = t; }
        public String _toString() { return "new " + t + "()"; }
    }
    
    public class NewArray extends Expr {
        public final Type.Array t;
        public final Expr[] dims;
        public NewArray(Type.Array t, Expr[] dims) { this.t = t; this.dims = dims; }
        public NewArray(Type.Array t, Expr dim) { this(t,new Expr[]{dim}); }
        public Type getType() { return t; }
        public String _toString() {
            Type base = t;
            int totalDims = 0;
            while(base.isArray()) {
                totalDims++;
                base = base.asArray().getElementType(); 
            }
            StringBuffer sb = new StringBuffer("new " + base);
            for(int i=0;i<totalDims;i++)
                sb.append("[" + (i < dims.length ? dims[i].toString() : "") + "]");
            return sb.toString();
        }
    }
    
    /** GETFIELD and GETSTATIC */
    public class Get extends Expr {
        final Type.Class.Field f;
        final Expr e;
        public Type getType() { return f.getType(); }
        public Get(Type.Class.Field f) { this(f, null); }
        public Get(Type.Class.Field f, Expr e) { this.f = f; this.e = e; }
        public String _toString() {
            return
                (e!=null
                 ? e+"."+f.name
                 : f.getDeclaringClass() == JSSA.this.method.getDeclaringClass()
                 ? f.name
                 : f.toString());
        }
    }

    /** PUTFIELD and PUTSTATIC */
    public class Put extends Op {
        final Type.Class.Field f;
        final Expr v;
        final Expr e;
        public Put(Type.Class.Field f, Expr v) { this(f, v, null); }
        public Put(Type.Class.Field f, Expr v, Expr e) { this.f = f; this.v = v; this.e = e; }
        public String toString() {
            return
                (e!=null
                 ? e+"."+f.name
                 : f.getDeclaringClass() == JSSA.this.method.getDeclaringClass()
                 ? f.name
                 : f.toString()) + " = " + v;
        }
    }

    public class ArrayPut extends Op {
        final Expr e, i, v;
        public ArrayPut(Expr v, Expr i, Expr e) { this.e = e; this.i = i; this.v = v; }
        public String toString() { return e + "[" + i + "] := " + v; }
    }

    public class ArrayGet extends Expr {
        final Expr e, i;
        public ArrayGet(Expr i, Expr e) { this.e = e; this.i = i; }
        public Type getType() { return e.getType().asArray().getElementType(); }
        public String _toString() { return e + "[" + i + "]"; }
    }

    public class ArrayLength extends Expr {
        final Expr e;
        public ArrayLength(Expr e) { this.e = e; }
        public Type getType() { return Type.INT; }
    }

    public abstract class Invoke extends Expr {
        public final Expr[] arguments;
        public final Type.Class.Method method;
        protected Invoke(Type.Class.Method m, Expr[] a) { this.arguments = a; this.method = m; } 

        public Type getType() { return method.getReturnType(); }
        protected void args(StringBuffer sb) {
            sb.append("(");
            for(int i=0; i<arguments.length; i++) {
                if (i>0) sb.append(", ");
                sb.append(arguments[i]+"");
            }
            sb.append(")");
        }

        public String _toString() {
            StringBuffer sb = new StringBuffer();
            sb.append(method.getDeclaringClass() == JSSA.this.method.getDeclaringClass()
                      ? method.name
                      : (method.getDeclaringClass() + "." + method.name));
            args(sb);
            return sb.toString();
        }
    }
    public class InvokeStatic    extends Invoke  { public InvokeStatic(Type.Class.Method m, Expr[] a) { super(m,a); } }
    public class InvokeSpecial   extends InvokeVirtual {
        public InvokeSpecial(Type.Class.Method m, Expr[] a, Expr e) { super(m,a,e); }
        public String _toString() { return _toString(method.name.equals("<init>")
                                                     ? method.getDeclaringClass().getName()
                                                     : method.name); }
    }
    public class InvokeInterface extends InvokeVirtual{
        public InvokeInterface(Type.Class.Method m, Expr[] a, Expr e) { super(m,a,e); } }
    public class InvokeVirtual   extends Invoke  {
        public final Expr instance;
        public InvokeVirtual(Type.Class.Method m, Expr[] a, Expr e) { super(m, a); instance = e; }
        public String _toString() { return _toString(method.name); }
        protected String _toString(String name) {
            StringBuffer sb = new StringBuffer();
            sb.append(instance+".");
            sb.append(name);
            args(sb);
            return sb.toString();
        }
    }

    public class Constant extends Expr {
        private final Object o;
        public Constant(int i) { this(new Integer(i)); }
        public Constant(Object o) { this.o = o; }
        public String toString() { return o == null ? "null" : o instanceof String ? "\"" + o + "\"" : o.toString(); }
        public Type getType() {
            if (o == null) return Type.NULL;
            if (o instanceof Byte) return Type.BYTE;
            if (o instanceof Short) return Type.SHORT;
            if (o instanceof Character) return Type.CHAR;
            if (o instanceof Boolean) return Type.BOOLEAN;
            if (o instanceof Long) return Type.LONG;
            if (o instanceof Double) return Type.DOUBLE;
            if (o instanceof Float) return Type.FLOAT;
            if (o instanceof Integer) return Type.INT;
            if (o instanceof String) return Type.STRING;
            throw new IllegalStateException("unknown constant type");
        }
    }


    // Implementation //////////////////////////////////////////////////////////////////////////////

    private Object addOp(int op, Object arg) {
        int i1 = 0;
        int i2 = 0;
        if (op==WIDE) {
            MethodGen.Wide w = (MethodGen.Wide)arg;
            op = w.op;
            arg = null;
            i1 = w.varNum;
            i2 = w.n;
        }
        if (op==IINC) {
            MethodGen.Pair p = (MethodGen.Pair)arg;
            arg = null;
            i1 = p.i1;
            i2 = p.i2;
        }
        if (arg instanceof Number) i1 = ((Integer)arg).intValue();
        Label label = (arg instanceof Label) ? (Label)arg : null;
        switch(op) {

            case NOP: return null;

                // Stack manipulations //////////////////////////////////////////////////////////////////////////////

            case ACONST_NULL:                                                          push(new Constant(null)); return null;
            case ICONST_M1:                                                            push(new Constant(-1));   return null;
            case ICONST_0: case LCONST_0: case FCONST_0: case DCONST_0:                push(new Constant(0));    return null;
            case ICONST_1: case LCONST_1: case FCONST_1: case DCONST_1:                push(new Constant(1));    return null;
            case ICONST_2: case FCONST_2:                                              push(new Constant(2));    return null;
            case ICONST_3:                                                             push(new Constant(3));    return null;
            case ICONST_4:                                                             push(new Constant(4));    return null;
            case ICONST_5:                                                             push(new Constant(5));    return null;
            case ILOAD:    case LLOAD:    case FLOAD:    case DLOAD:    case ALOAD:    push(locals[pc][i1]);     return null;
            case ILOAD_0:  case LLOAD_0:  case FLOAD_0:  case DLOAD_0:  case ALOAD_0:  push(locals[pc][0]);      return null;
            case ILOAD_1:  case LLOAD_1:  case FLOAD_1:  case DLOAD_1:  case ALOAD_1:  push(locals[pc][1]);      return null;
            case ALOAD_2:  case DLOAD_2:  case FLOAD_2:  case LLOAD_2:  case ILOAD_2:  push(locals[pc][2]);      return null;
            case ILOAD_3:  case LLOAD_3:  case FLOAD_3:  case DLOAD_3:  case ALOAD_3:  push(locals[pc][3]);      return null;
            case ISTORE:   case LSTORE:   case FSTORE:   case DSTORE:   case ASTORE:
                locals[pc+1][i1].merge(pop()); return null;
            case ISTORE_0: case LSTORE_0: case FSTORE_0: case DSTORE_0: case ASTORE_0:
                locals[pc+1][0].merge(pop()); return null;
            case ISTORE_1: case LSTORE_1: case FSTORE_1: case DSTORE_1: case ASTORE_1:
                locals[pc+1][1].merge(pop()); return null;
            case ASTORE_2: case DSTORE_2: case FSTORE_2: case LSTORE_2: case ISTORE_2:
                locals[pc+1][2].merge(pop()); return null;
            case ISTORE_3: case LSTORE_3: case FSTORE_3: case DSTORE_3: case ASTORE_3:
                locals[pc+1][3].merge(pop()); return null;
            case POP:                                                                  pop();                    return null;
            case POP2:                                                                 pop(); pop();             return null;
            case DUP:   push(stack[sp-1]);                  return null;
            case DUP2:  push(stack[sp-2]); push(stack[sp-1]); return null;

                // Conversions //////////////////////////////////////////////////////////////////////////////

                // coercions are added as-needed when converting from JSSA back to bytecode, so we can
                // simply discard them here (assuming the bytecode we're reading in was valid in the first place)

            case I2L: case F2L: case D2L:               push(new Cast(pop(), Type.LONG)); return null;
            case I2F: case L2F: case D2F:               push(new Cast(pop(), Type.FLOAT)); return null;
            case I2D: case L2D: case F2D:               push(new Cast(pop(), Type.DOUBLE)); return null;
            case L2I: case F2I: case D2I:               push(new Cast(pop(), Type.INT)); return null;
            case I2B:                                   push(new Cast(pop(), Type.BYTE)); return null;
            case I2C:                                   push(new Cast(pop(), Type.CHAR)); return null;
            case I2S:                                   push(new Cast(pop(), Type.SHORT)); return null;
            case SWAP:                                  { Expr e1 = pop(), e2 = pop(); push(e2);  push(e1); return null; }

                // Math //////////////////////////////////////////////////////////////////////////////
                   
            case IADD: case LADD: case FADD: case DADD: push(new Add(pop(), pop()));  return null;
            case ISUB: case LSUB: case FSUB: case DSUB: push(new Sub(pop(), pop()));  return null;
            case IMUL: case LMUL: case FMUL: case DMUL: push(new Mul(pop(), pop()));  return null;
            case IREM: case LREM: case FREM: case DREM: push(new Rem(pop(), pop()));  return null;
            case INEG: case LNEG: case FNEG: case DNEG: push(new Neg(pop()));         return null;
            case IDIV: case LDIV: case FDIV: case DDIV: push(new Div(pop(), pop()));  return null;
            case ISHL: case LSHL:                       push(new Shl(pop(), pop()));  return null;
            case ISHR: case LSHR:                       push(new Shr(pop(), pop()));  return null;
            case IUSHR: case LUSHR:                     push(new Ushr(pop(), pop())); return null;
            case IAND: case LAND:                       push(new And(pop(), pop()));  return null;
            case IOR:  case LOR:                        push(new Or(pop(), pop()));   return null;
            case IXOR: case LXOR:                       push(new Xor(pop(), pop()));  return null;
            case IINC:                                  return locals[pc+1][i1] = phi(new Add(locals[pc][i1], new Constant(i2)));

                // Control and branching //////////////////////////////////////////////////////////////////////////////

            case IFNULL:                                return new If(new Eq(pop(), new Constant(null)), new Label(i1));
            case IFNONNULL:                             return new If(new Not(new Eq(pop(),new Constant(null))),new Label(i1));
            case IFEQ:                                  return new If(    new Eq(new Constant(0), pop()), new Label(i1));
            case IFNE:                                  return new If(new Not(new Eq(new Constant(0), pop())), new Label(i1));
            case IFLT:                                  return new If(    new Lt(new Constant(0), pop()), new Label(i1));
            case IFGE:                                  return new If(new Not(new Lt(new Constant(0), pop())), new Label(i1));
            case IFGT:                                  return new If(    new Gt(new Constant(0), pop()), new Label(i1));
            case IFLE:                                  return new If(new Not(new Gt(new Constant(0), pop())), new Label(i1));
            case IF_ICMPEQ:                             return new If(    new Eq(pop(), pop()), new Label(i1));
            case IF_ICMPNE:                             return new If(new Not(new Eq(pop(), pop())), new Label(i1));
            case IF_ICMPLT:                             return new If(    new Lt(pop(), pop()), new Label(i1));
            case IF_ICMPGE:                             return new If(new Not(new Lt(pop(), pop())), new Label(i1));
            case IF_ICMPGT:                             return new If(    new Gt(pop(), pop()), new Label(i1));
            case IF_ICMPLE:                             return new If(new Not(new Gt(pop(), pop())), new Label(i1));
            case IF_ACMPEQ:                             return new If(    new Eq(pop(), pop()), new Label(i1));
            case IF_ACMPNE:                             return new If(new Not(new Eq(pop(), pop())), new Label(i1));
            case ATHROW:                                                       return new Throw(pop());
            case GOTO:                                                         return new Goto(locals[pc][i1]);
            case JSR:                                   push(new Label(pc));   return new JSR(new Label(i1));
            case RET:                                                          return new RET(pop());
            case RETURN:                                                       return new Return();
            case IRETURN: case LRETURN: case FRETURN: case DRETURN: case ARETURN:
                return new Return(pop());

                // Array manipulations //////////////////////////////////////////////////////////////////////////////

            case IALOAD:  case LALOAD:  case FALOAD:  case DALOAD:  case AALOAD:
            case BALOAD:  case CALOAD:  case SALOAD:
                return seqPush(new ArrayGet(pop(), pop()));
            case IASTORE: case LASTORE: case FASTORE: case DASTORE: case AASTORE:
            case BASTORE: case CASTORE: case SASTORE:
                return new ArrayPut(pop(), pop(), pop());

                // Invocation //////////////////////////////////////////////////////////////////////////////

            case INVOKEVIRTUAL: case INVOKESPECIAL: case INVOKESTATIC: case INVOKEINTERFACE: {
                Type.Class.Method method = (Type.Class.Method)arg;
                Expr args[] = new Expr[method.getNumArgs()];
                for(int i=0; i<args.length; i++) args[args.length-i-1] = pop();
                Expr ret;
                switch(op) {
                    case INVOKEVIRTUAL:   ret = new InvokeVirtual(method, args, pop()); break;
                    case INVOKEINTERFACE: ret = new InvokeInterface(method, args, pop()); break;
                    case INVOKESPECIAL:   ret = new InvokeSpecial(method, args, pop()); break;
                    case INVOKESTATIC:    ret = new InvokeStatic(method, args); break;
                    default: throw new Error("should never happen");
                }
                if (ret.getType() != Type.VOID) push(ret);
                return new Seq(ret);
            }

                // Field Access //////////////////////////////////////////////////////////////////////////////

            case GETSTATIC:         return seqPush(new Get((Type.Class.Field)arg, null));
            case PUTSTATIC:         return new Put((Type.Class.Field)arg, pop(), null);
            case GETFIELD:          return seqPush(new Get((Type.Class.Field)arg, pop()));
            case PUTFIELD:          return new Put((Type.Class.Field)arg, pop(), pop());

                // Allocation //////////////////////////////////////////////////////////////////////////////

            case NEW:               push(new New((Type.Class)arg)); return null;
            case NEWARRAY:          return seqPush(new NewArray(Type.fromArraySpec(((Integer)arg).intValue()).makeArray(), pop()));
            case ANEWARRAY:         push(new NewArray(((Type.Ref)arg).makeArray(), pop())); return null;
            case MULTIANEWARRAY: {
                MethodGen.MultiANewArray mana = (MethodGen.MultiANewArray) arg;
                Expr[] dims = new Expr[mana.dims];
                for(int i=0;i<dims.length;i++) dims[i] = pop();
                return seqPush(new NewArray(mana.type, dims));
            }
            case ARRAYLENGTH:       return seqPush(new ArrayLength(pop()));

                // Runtime Type information //////////////////////////////////////////////////////////////////////////////

            case CHECKCAST:         return seqPush(new Cast(pop(), (Type.Ref)arg));
            case INSTANCEOF:        push(new InstanceOf(pop(), (Type.Ref)arg)); return null;

            case LDC: case LDC_W: case LDC2_W: push(new Constant(arg)); return null;

            case BIPUSH:    push(new Constant((Integer)arg)); return null;
            case SIPUSH:    push(new Constant((Integer)arg)); return null;

            case TABLESWITCH:    return new Branch((MethodGen.Switch)arg);
            case LOOKUPSWITCH:   return new Branch((MethodGen.Switch)arg);

                /* FIXME
            case MONITORENTER:   Op.monitorEnter(pop());
            case MONITOREXIT:    Op.monitorExit(pop());
                */

            case DUP_X1:         throw new Error("unimplemented");
            case DUP_X2:         throw new Error("unimplemented");
            case DUP2_X1:         throw new Error("unimplemented");
            case DUP2_X2:         throw new Error("unimplemented");
            case LCMP:         throw new Error("unimplemented");
            case FCMPL:         throw new Error("unimplemented");
            case FCMPG:         throw new Error("unimplemented");
            case DCMPL:         throw new Error("unimplemented");
            case DCMPG:         throw new Error("unimplemented");
            case GOTO_W:         throw new Error("unimplemented");
            case JSR_W:         throw new Error("unimplemented");
            default:          throw new Error("unhandled");
        }
    }

    
    public void debugBodyToString(StringBuffer sb) {
        StringBuffer sb0 = new StringBuffer();
        super.debugBodyToString(sb0);
        StringTokenizer st = new StringTokenizer(sb0.toString(), "\n");
        String[] lines = new String[st.countTokens()];
        for(int i=0; i<lines.length; i++) lines[i] = st.nextToken();
        for(int j=0; j<ofs[0]; j++) {
            String s = "    /* " + lines[j].trim();
             while(s.length() < 50) s += " ";
             s += " */";
             sb.append(s);
             sb.append("\n");
        }
        
        bindingMap = new IdentityHashMap();
        nextVar = 0;
        
        for(int i=0; i<numOps; i++) {
            String s = "    /* " + lines[ofs[i]].trim();
             while(s.length() < 50) s += " ";
             s += " */  ";
             s += ops[i].toString();
             sb.append(s);
             sb.append(";\n");
             for(int j=ofs[i]+1; j<(i==numOps-1?size():ofs[i+1]); j++) {
                 s = "    /* " + lines[j].trim();
                  while(s.length() < 50) s += " ";
                  s += " */";
                  sb.append(s);
                  sb.append("\n");
             }
        }
    }
    
    private Map bindingMap;
    private int nextVar;
    
    public static void main(String[] args) throws Exception {
        InputStream is = Class.forName(args[0]).getClassLoader().getResourceAsStream(args[0].replace('.', '/')+".class");
        System.out.println(new ClassFile(new DataInputStream(is), true).toString());
    }
}
