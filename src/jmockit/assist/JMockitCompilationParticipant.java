/*
 * Copyright (c) 2012 Andrejs Jermakovics.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Andrejs Jermakovics - initial implementation
 */
package jmockit.assist;

import static org.eclipse.jdt.core.dom.Modifier.isPrivate;

import java.util.ArrayList;
import java.util.List;

import jmockit.assist.prefs.Prefs;
import jmockit.assist.prefs.Prefs.CheckScope;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.BuildContext;
import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.core.compiler.CompilationParticipant;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.compiler.ReconcileContext;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblem;
import org.eclipse.jdt.internal.compiler.problem.ProblemSeverities;
import org.eclipse.ui.progress.IProgressConstants;

@SuppressWarnings("restriction")
/**
 * Compilation participant to check mock classes and methods for problems
 */
public final class JMockitCompilationParticipant extends CompilationParticipant
{
	public static final String MARKER = "jmockit.eclipse.marker";

	public JMockitCompilationParticipant()
	{
	}

	@Override
	public void buildFinished(final IJavaProject project)
	{
		System.err.println("build finished");
	}

	private static class AnalysisJob extends WorkspaceJob
	{
		private List<BuildContext> files;

		public AnalysisJob(final List<BuildContext> filesPar)
		{
			super("JMockit analysis");
			this.files = filesPar;
			setProperty(IProgressConstants.KEEP_PROPERTY, Boolean.TRUE);
		}

		@Override
		public IStatus runInWorkspace(final IProgressMonitor mon) throws CoreException
		{
			mon.beginTask("Analyzing files", files.size());
			for(BuildContext f: files)
			{
				ICompilationUnit cunit = JavaCore.createCompilationUnitFrom(f.getFile());

				try
				{
					if (cunit != null && cunit.isStructureKnown() )
					{
						mon.subTask("Parsing " + cunit.getElementName());
						CompilationUnit cu = ASTUtil.getAstOrParse(cunit, mon);

						MockASTVisitor visitor = new MockASTVisitor(cunit);
						cu.accept(visitor);
						f.recordNewProblems(visitor.getProblems());
					}
				}
				catch (JavaModelException e)
				{
					Activator.log(e);
					return Status.CANCEL_STATUS;
				}

				if( mon.isCanceled() )
				{
					break;
				}

				mon.worked(1);
			}

			return Status.OK_STATUS;
		}
	}

	@Override
	public void buildStarting(final BuildContext[] files, final boolean isBatch)
	{
		CheckScope scope = getCheckScope();
		if( scope == CheckScope.Disabled )
		{
			return;
		}

		IResource res = Activator.getActiveResource();
		String activeProj = null;
		if( res != null )
		{
			activeProj = res.getProject().getName();
		}

		List<BuildContext> filesToParse = new ArrayList<BuildContext>();
		for (BuildContext f : files)
		{
			IFile file = f.getFile();

			if( (scope == CheckScope.Project || scope == CheckScope.File )
					&& !file.getProject().getName().equals(activeProj))
			{
				continue;
			}

			if( scope == CheckScope.File )
			{
				if( res == null || !file.equals(res) )
				{
					continue;
				}
			}

			filesToParse.add(f);
		}

		AnalysisJob job = new AnalysisJob(filesToParse);
		job.setSystem(false);
		job.schedule();
	}

	public static CheckScope getCheckScope()
	{
		String propVal = Activator.getPrefStore().getString(Prefs.PROP_CHECK_SCOPE);
		CheckScope scope = CheckScope.Workspace;

		try
		{
			scope = CheckScope.valueOf(propVal);
		}
		catch(Exception e)
		{
			Activator.log(e);
		}
		return scope;
	}

	@Override
	public boolean isActive(final IJavaProject project)
	{
		CheckScope checkScope = getCheckScope();

		if( checkScope == CheckScope.Disabled )
		{
			return false;
		}

		String activeProj = Activator.getActiveProject();

		if( (checkScope == CheckScope.Project || checkScope == CheckScope.File )
				&& !project.getProject().getName().equals(activeProj) )
		{
			return false;
		}

		IType mockitType = null;
		try
		{
			mockitType = project.findType(MockUtil.MOCKIT);
		}
		catch (JavaModelException e)
		{
			Activator.log(e);
		}

		return mockitType != null;
	}

	@Override
	public void reconcile(final ReconcileContext context)
	{
		if( getCheckScope() == CheckScope.Disabled )
		{
			return;
		}

		try
		{
			MockASTVisitor visitor = new MockASTVisitor(context.getWorkingCopy());
			context.getAST3().accept(visitor);

			CategorizedProblem[] probs = visitor.getProblems();

			if (probs.length != 0)
			{
				context.putProblems(probs[0].getMarkerType(), probs);
			}
		}
		catch (JavaModelException e)
		{
			Activator.log(e);
		}
	}
	public static final class MockASTVisitor extends ASTVisitor
	{
		private final ICompilationUnit icunit;
		private CompilationUnit cu;
		private boolean hasMockitImport = false;

		private final List<CategorizedProblem> probs = new ArrayList<CategorizedProblem>();

		public MockASTVisitor(final ICompilationUnit cunitPar)
		{
			icunit = cunitPar;
		}

		@Override
		public boolean visit(final CompilationUnit node)
		{
			cu = node;
			return true;
		}

		@Override
		public boolean visit(final MethodDeclaration node)
		{
			IMethodBinding meth = node.resolveBinding();
			ITypeBinding mockedType = MockUtil.findMockedType(node, meth); // new MockUp< MockedType >

			if ( mockedType != null )
			{
				boolean hasMockAnn = MockUtil.isMockMethod(meth);
				IMethodBinding origMethod = findRealMethod(node, meth, mockedType);

				if (!hasMockAnn && origMethod != null)
				{
					addMarker(node.getName(), "Mocked method missing @Mock annotation", false);
				}

				if (hasMockAnn && origMethod == null)
				{
					addMarker(node.getName(), "Mocked real method not found in type ", true);
				}

				if (hasMockAnn && origMethod != null && isPrivate(meth.getModifiers()))
				{
					addMarker(node.getName(), "Mocked method should not be private", true);
				}
			}

			return true;
		}

		public IMethodBinding findRealMethod(final MethodDeclaration node,
				final IMethodBinding meth, final ITypeBinding mockedType)
		{
			IMethodBinding origMethod = null;

			if (mockedType != null)
			{
				String name = meth.getName();

				if ( MockUtil.CTOR.equals(name)) // constructor
				{
					name = mockedType.getName();
				}

				ITypeBinding[] parameterTypes = meth.getParameterTypes(); // .getErasure()
				origMethod = MockUtil.findRealMethodInType(mockedType, name, parameterTypes, node.getAST());
			}
			return origMethod;
		}

		@Override
		public boolean visit(final AnonymousClassDeclaration node)
		{
			ITypeBinding binding = node.resolveBinding();

			if ( MockUtil.isMockUpType(binding.getSuperclass()) ) // new MockUp< type >
			{
				ITypeBinding typePar = ASTUtil.getFirstTypeParameter(node);
				ASTNode parent = node.getParent();

				if (parent instanceof ClassInstanceCreation && typePar.isInterface()  ) // creating interface mock
				{
					ClassInstanceCreation creation = (ClassInstanceCreation) parent;

					visitMockUpCreation(creation);
				}

			}

			return true;
		}

		public void visitMockUpCreation(final ClassInstanceCreation creation)
		{
			ASTNode gparent = creation.getParent();

			Type typeNode = creation.getType();
			boolean invokesGetInst = false;

			if (gparent instanceof MethodInvocation) // method invocation follows
			{
				MethodInvocation inv = (MethodInvocation) gparent;
				IMethodBinding meth = inv.resolveMethodBinding();

				if ( MockUtil.GET_INST.equals(meth.getName()))
				{
					invokesGetInst = true;
					if (gparent.getParent() instanceof ExpressionStatement)
					{
						addMarker(inv.getName(), "Returned mock instance is not being used.", false);
					}
				}
			}

			if( !invokesGetInst )
			{
				addMarker(typeNode, "Missing call to getMockInstance() on MockUp of interface",
						false);
			}
		}

		@Override
		public boolean visit(final MethodInvocation node)
		{
			IMethodBinding meth = node.resolveMethodBinding();

			if (meth == null)
			{
				return false;
			}

			if ( MockUtil.isMockUpType(meth.getDeclaringClass()) && MockUtil.GET_INST.equals(meth.getName()))
			{
				ITypeBinding returnType = node.resolveTypeBinding();

				if (!returnType.isInterface())
				{
					String msg = "getMockInstance() used on mock of class " + returnType.getName()
							+ ". Use on interfaces";
					addMarker(node.getName(), msg, false);
				}
			}

			visitItFieldInvocation(node, meth);

			return true;
		}

		// consider visit(FieldAccess )
		private void visitItFieldInvocation(final MethodInvocation node, final IMethodBinding meth)
		{
			if (node.getExpression() instanceof SimpleName)
			{
				SimpleName var = (SimpleName) node.getExpression();
				String varName = var.getIdentifier();

				IBinding exprBinding = var.resolveBinding();

				if ( "it".equals(varName) && exprBinding instanceof IVariableBinding ) // call on 'it' field
				{
					IVariableBinding varBinding = (IVariableBinding) exprBinding;
					IMethodBinding mockMethod = getSurroundingMockMethod(node);

					if( mockMethod != null && varBinding.isField() && mockMethod.isSubsignature(meth)
							&& varBinding.getDeclaringClass().equals(mockMethod.getDeclaringClass()) )
					{
						ITypeBinding mockedType = MockUtil.findMockedType(node);

						if( mockedType.equals(varBinding.getType()) // field type is correct mocked type
								&& !MockUtil.isReentrantMockMethod(mockMethod) )
						{
							addMarker( node,
									"Method calls itself. Set @Mock(reentrant=true) on method '"
											+ mockMethod.getName() + "'", true);
						}
					}
				}
			}
		}

		private IMethodBinding getSurroundingMockMethod(final MethodInvocation node)
		{
			MethodDeclaration surroundingMeth = ASTUtil.findAncestor(node, MethodDeclaration.class);
			IMethodBinding mockMethod = null;

			if (surroundingMeth != null)
			{
				mockMethod = surroundingMeth.resolveBinding();
			}

			if( mockMethod != null && MockUtil.isMockMethod(mockMethod) )
			{
				return mockMethod;
			}
			else
			{
				return null;
			}
		}

		@Override
		public boolean visit(final FieldDeclaration node)
		{
			//add checks:
			// - 'it' field should have the correct type
			// - it field should not be private
			// - adding other fields to a  mock - warning about shared state?

//			for(Object fragment: node.fragments())
//			{
//				if(fragment instanceof VariableDeclarationFragment)
//				{
//					VariableDeclarationFragment varDec = (VariableDeclarationFragment) fragment;
//
//					if( "it".equals(varDec.getName().getIdentifier()) )
//					{
//						IVariableBinding ivar = varDec.resolveBinding();
//
//						if( ASTUtil.isMockUpType(ivar.getDeclaringClass().getSuperclass()) )
//						{
//						}
//					}
//				}
//			}

			return true;
		}

		@Override
		public boolean visit(final TypeDeclaration node)
		{
			return hasMockitImport;
		}

		@Override
		public boolean visit(final ImportDeclaration node)
		{
			String importName = node.getName().getFullyQualifiedName();
			hasMockitImport = hasMockitImport || importName.startsWith("mockit.Mock");
			return true;
		}

		private void addMarker(final ASTNode node, final String msg, final boolean isError)
		{
			try
			{
				int start = node.getStartPosition();
				int endChar = start + node.getLength();
				int line = cu.getLineNumber(start);
				int col = cu.getColumnNumber(start);
				int id = IProblem.TypeRelated;
				String filePath = icunit.getPath().toOSString();
				String[] args = new String[]{};

				int severity = isError ? ProblemSeverities.Error : ProblemSeverities.Warning;

				CategorizedProblem problem = new DefaultProblem(filePath.toCharArray(), msg, id,
						args, severity, start, endChar, line, col)
				{
					@Override
					public String getMarkerType()
					{
						return MARKER;
					}
				};

				probs.add(problem);
			}
			catch (Exception e)
			{
				Activator.log(e);
			}
		}

		public CategorizedProblem[] getProblems()
		{
			return probs.toArray(new CategorizedProblem[]{});
		}

	}

}