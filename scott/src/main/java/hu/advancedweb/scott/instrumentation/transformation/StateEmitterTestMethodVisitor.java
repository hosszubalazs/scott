package hu.advancedweb.scott.instrumentation.transformation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Instruments test methods to call Scott Runtime to track variable states.
 * 
 * @author David Csakvari
 */
public class StateEmitterTestMethodVisitor extends MethodVisitor {

	private int lineNumber;
	private int lineNumberForMethodCallTrack;
	
	private Set<Integer> localVariables = new HashSet<>();
	
	private List<LocalVariableScope> localVariableScopes = new ArrayList<>();

	private Set<AccessedField> accessedFields;

	private String methodName;

	private String className;

	private boolean clearTrackedDataAtStart;
	

	public StateEmitterTestMethodVisitor(MethodVisitor mv, String className, String methodName, boolean clearTrackedDataAtStart) {
		super(Opcodes.ASM5, mv);
		
		Logger.log("Visiting: " + className + "." + methodName);
		
		this.className = className;
		this.methodName = methodName;
		this.clearTrackedDataAtStart = clearTrackedDataAtStart;
	}
	
	@Override
	public void visitCode() {
		super.visitCode();
		
		// clear previously tracked data
		if (clearTrackedDataAtStart) {
			instrumentToClearTrackedDataAndSignalStartOfRecording();
		}
		
		// track initial field states
		for (AccessedField accessedField : accessedFields) {
			instrumentToTrackFieldState(accessedField, lineNumber);
		}
		
		// track method arguments
		for (LocalVariableScope localVariableScope : localVariableScopes) {
			if (localVariableScope.start == 0) {
				instrumentToTrackVariableName(localVariableScope, lineNumber);
				instrumentToTrackVariableState(localVariableScope, lineNumber);
			}
		}
	}
	
	@Override
	public void visitLineNumber(int lineNumber, Label label) {
		this.lineNumberForMethodCallTrack = this.lineNumber;
		this.lineNumber = lineNumber;
		super.visitLineNumber(lineNumber, label);
	}
	
	@Override
	public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
		localVariables.clear();
		return super.visitAnnotation(desc, visible);
	}
	
	@Override
	public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... bsmArgs) {
		/*
		 * Track where lambda expressions are defined.
		 */
		if ("java/lang/invoke/LambdaMetafactory".equals(bsm.getOwner())) {
			if (bsmArgs[1] instanceof Handle) {
				Handle handle = (Handle)bsmArgs[1];
				String methodName = handle.getName();
				if (methodName.startsWith("lambda$")) {
					instrumentToTrackMethodStart(handle.getName(), lineNumber);
				}
			}
		}
		
		super.visitInvokeDynamicInsn(name, desc, bsm, bsmArgs);
	}
	
	@Override
    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
		if (this.lineNumberForMethodCallTrack == 0) {
			this.lineNumberForMethodCallTrack = this.lineNumber;
		}
		
		if (!owner.startsWith("org/mockito")) {
			// track every variable state after method calls
			for (LocalVariableScope localVariableScope : localVariableScopes) {
				if (!localVariables.contains(localVariableScope.var)) continue;
				
				if (isVariableInScope(localVariableScope.var)) {
					instrumentToTrackVariableState(localVariableScope, lineNumberForMethodCallTrack);
				}
			}
			
			// track every field state after method calls
			for (AccessedField accessedField : accessedFields) {
				instrumentToTrackFieldState(accessedField, lineNumberForMethodCallTrack);
			}
		}
		
		this.lineNumberForMethodCallTrack = this.lineNumber;
		
		/*
		 * Visit the method instruction after placing the tracking code
		 * because otherwise we might confuse Mockito, see Issue #25.
		 * Because of this, the tracking code has to book the values to the previous line
		 * for the first method call for every line.
		 */
		super.visitMethodInsn(opcode, owner, name, desc, itf);
    }
	
	@Override
	public void visitVarInsn(int opcode, int var) {
		super.visitVarInsn(opcode, var);
		
		// Track variable state and name at variable stores. (Typical variable assignments.)
		if (VariableType.isStoreOperation(opcode)) {
			localVariables.add(var);
			
			LocalVariableScope lvs = getLocalVariableScope(var);
			if (lvs != null) {
				/* 
				 * This null-check is the workaround for issue #15:
				 * If a variable declaration is the last statement in a code block,
				 * then the variable name is not present in the compiled bytecode.
				 * With this workaround Scott can still track the assigned value to such variables.
				 */
				instrumentToTrackVariableName(lvs, lineNumber);
				instrumentToTrackVariableState(lvs, lineNumber);
			}
		}
	}
	
	@Override
	public void visitIincInsn(int var, int increment) {
		super.visitIincInsn(var, increment);
		
		// Track variable state at variable increases (e.g. i++).
		LocalVariableScope lvs = getLocalVariableScope(var);
		instrumentToTrackVariableState(lvs, lineNumber);
	}
	
	private void instrumentToClearTrackedDataAndSignalStartOfRecording() {
		Logger.log(" - instrumentToClearTrackedDataAndSignalStartOfRecording");
		super.visitLdcInsn(className);
		super.visitLdcInsn(methodName);
		super.visitMethodInsn(Opcodes.INVOKESTATIC, "hu/advancedweb/scott/runtime/track/StateRegistry", "startTracking", "(Ljava/lang/String;Ljava/lang/String;)V", false);
	}
	
	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String desc) {
		super.visitFieldInsn(opcode, owner, name, desc);
		if (Opcodes.PUTFIELD == opcode|| Opcodes.PUTSTATIC == opcode) {
			for (AccessedField accessedField : accessedFields) {
				if (accessedField.name.equals(name)) {
					instrumentToTrackFieldState(accessedField, lineNumber);
					break;
				}
			}
		}
	}
	
	private void instrumentToTrackMethodStart(String methodName, int lineNumber) {
		Logger.log(" - instrumentToTrackMethodStart of " + methodName + " at " + lineNumber);
		super.visitLdcInsn(lineNumber);
		super.visitLdcInsn(methodName);
		super.visitMethodInsn(Opcodes.INVOKESTATIC, "hu/advancedweb/scott/runtime/track/StateRegistry", "trackMethodStart", "(ILjava/lang/String;)V", false);
	}
	
	private void instrumentToTrackVariableState(LocalVariableScope localVariableScope, int lineNumber) {
		Logger.log(" - instrumentToTrackVariableState of variable at " + getLineNumberBoundedByScope(lineNumber, localVariableScope) + ": " + localVariableScope);
		super.visitVarInsn(localVariableScope.variableType.loadOpcode, localVariableScope.var);
		super.visitLdcInsn(getLineNumberBoundedByScope(lineNumber, localVariableScope));
		super.visitLdcInsn(localVariableScope.var);
		super.visitLdcInsn(methodName);
		super.visitMethodInsn(Opcodes.INVOKESTATIC, "hu/advancedweb/scott/runtime/track/StateRegistry", "trackLocalVariableState", "(" + localVariableScope.variableType.desc + "IILjava/lang/String;)V", false);
	}
	
	private int getLineNumberBoundedByScope(int lineNumber, LocalVariableScope localVariableScope) {
		return Math.min(localVariableScope.end, Math.max(lineNumber, localVariableScope.start));
	}
	
	private void instrumentToTrackFieldState(AccessedField accessedField, int lineNumber) {
		Logger.log(" - instrumentToTrackFieldState at " + lineNumber + ": " + accessedField);
		final int opcode;
		if (accessedField.isStatic) {
			opcode = Opcodes.GETSTATIC;
		} else {
			opcode = Opcodes.GETFIELD;
			super.visitVarInsn(Opcodes.ALOAD, 0);
		}

		String desc = accessedField.desc;
		if (desc.startsWith("L") || desc.startsWith("[")) {
			desc = VariableType.REFERENCE.desc;
		}
		
		super.visitFieldInsn(opcode, accessedField.owner, accessedField.name, accessedField.desc);
		super.visitLdcInsn(accessedField.name);
		super.visitLdcInsn(lineNumber);
		super.visitLdcInsn(accessedField.isStatic);
		super.visitLdcInsn(accessedField.owner);
		super.visitMethodInsn(Opcodes.INVOKESTATIC, "hu/advancedweb/scott/runtime/track/StateRegistry", "trackFieldState", "(" + desc + "Ljava/lang/String;IZLjava/lang/String;)V", false);
	}
	
	private void instrumentToTrackVariableName(LocalVariableScope localVariableScope, int lineNumber) {
		Logger.log(" - instrumentToTrackVariableName at " + getLineNumberBoundedByScope(lineNumber, localVariableScope) + ": " + localVariableScope);
		super.visitLdcInsn(localVariableScope.name);
		super.visitLdcInsn(getLineNumberBoundedByScope(lineNumber, localVariableScope));
		super.visitLdcInsn(localVariableScope.var);
		super.visitLdcInsn(methodName);
		super.visitMethodInsn(Opcodes.INVOKESTATIC, "hu/advancedweb/scott/runtime/track/StateRegistry", "trackVariableName", "(Ljava/lang/String;IILjava/lang/String;)V", false);
	}
	
	private boolean isVariableInScope(int var) {
		return getLocalVariableScope(var) != null;
	}
	
	private LocalVariableScope getLocalVariableScope(int var) {
		// check the scopes in reverse order in case of multiple var declarations on the same line
		List<LocalVariableScope> localVariableScopesReversed = new ArrayList<>(localVariableScopes);
		Collections.reverse(localVariableScopesReversed);
		
		for (LocalVariableScope localVariableScope : localVariableScopes) {
			if (localVariableScope.var == var &&
					localVariableScope.start <= lineNumber &&
					localVariableScope.end >= lineNumber) {
				return localVariableScope;
			}
		}
		return null;
	}
	
	public void setLocalVariableScopes(List<LocalVariableScope> localVariableScopes) {
		this.localVariableScopes = localVariableScopes;
	}
	
	public void setAccessedFields(Set<AccessedField> accessedFields) {
		this.accessedFields = accessedFields;
	}
	
}
