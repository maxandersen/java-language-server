package org.jboss.tools.vscode.java.managers;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.URIUtil;
import org.eclipse.jdt.core.CompletionContext;
import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.CompletionRequestor;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IProblemRequestor;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.jboss.tools.vscode.ipc.JsonRpcConnection;
import org.jboss.tools.vscode.java.CompletionProposalRequestor;
import org.jboss.tools.vscode.java.HoverInfoProvider;
import org.jboss.tools.vscode.java.handlers.DiagnosticsHandler;
import org.jboss.tools.vscode.java.handlers.JsonRpcHelpers;
import org.jboss.tools.vscode.java.model.CodeCompletionItem;
import org.jboss.tools.vscode.java.model.Location;
import org.jboss.tools.vscode.java.model.SymbolInformation;

/**
 * Manages the life-cycle of documents edited on VS Code.
 * 
 * @author Gorkem Ercan
 *
 */
public class DocumentsManager {

	private Map<String, ICompilationUnit> openUnits;
	private ProjectsManager pm;
	private JsonRpcConnection connection;
	
	public DocumentsManager(JsonRpcConnection conn, ProjectsManager pm  ) {
		openUnits =  new HashMap<String,ICompilationUnit>();
		this.pm = pm;
		this.connection = conn;
	}
	
	
	public ICompilationUnit openDocument(String uri){
		JsonRpcConnection.log("Opening document : " + uri);
		ICompilationUnit unit = openUnits.get(uri);
		if (unit == null) {
			File f = null;
			try {
				f = URIUtil.toFile(new URI(uri));
			} catch (URISyntaxException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			if (f != null) {
				IPath p = Path.fromOSString(f.getAbsolutePath());
				IFile file = ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(p);
				if (file != null) {
					try {
						final DiagnosticsHandler pe = new DiagnosticsHandler(connection, uri);
						unit = ((ICompilationUnit) JavaCore.create(file)).getWorkingCopy(new WorkingCopyOwner() {

							@Override
							public IProblemRequestor getProblemRequestor(ICompilationUnit workingCopy) {
								return pe;
							}

						}, new NullProgressMonitor());

					} catch (JavaModelException e) {
						// TODO: handle exception

					}
					openUnits.put(uri, unit);
					JsonRpcConnection.log("added unit " + uri);
				}
			}
		}
		if(unit != null){
			try {
				unit.reconcile(ICompilationUnit.NO_AST, true, null, null);
			} catch (JavaModelException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		return unit;
			
	}
	
	public void closeDocument(String uri){
		JsonRpcConnection.log("close document : " + uri);
		openUnits.remove(uri);
	}
	
	public void updateDocument(String uri, int line, int column, int length, String text){
		JsonRpcConnection.log("Updating document: "+ uri+ " line: " + line + " col:" +column + " length:"+ length +" text:"+text );
		ICompilationUnit unit = openUnits.get(uri);
		if(unit == null ) return ;
		try {
			IBuffer buffer = unit.getBuffer();
			int offset = JsonRpcHelpers.toOffset(buffer,line,column);
			buffer.replace(offset,length,text);
			JsonRpcConnection.log("Changed buffer: "+buffer.getContents());
			
			if (length > 0 || text.length() > 0) {
				JsonRpcConnection.log(uri+ " updated reconciling");
				unit.reconcile(ICompilationUnit.NO_AST, true, null, null);
			}

		} catch (JavaModelException e) {
			JsonRpcConnection.log(e.toString());
			e.printStackTrace();
		}
	}
	
	public List<CodeCompletionItem> computeContentAssist(String uri, int line, int column){
		ICompilationUnit unit = openUnits.get(uri);
		if(unit == null ) return Collections.emptyList();
		final List<CodeCompletionItem> proposals = new ArrayList<CodeCompletionItem>();
		final CompletionContext[] completionContextParam = new CompletionContext[] { null };
		try {
			CompletionRequestor collector = new CompletionProposalRequestor(unit, proposals);
			// Allow completions for unresolved types - since 3.3
			collector.setAllowsRequiredProposals(CompletionProposal.FIELD_REF, CompletionProposal.TYPE_REF, true);
			collector.setAllowsRequiredProposals(CompletionProposal.FIELD_REF, CompletionProposal.TYPE_IMPORT, true);
			collector.setAllowsRequiredProposals(CompletionProposal.FIELD_REF, CompletionProposal.FIELD_IMPORT, true);

			collector.setAllowsRequiredProposals(CompletionProposal.METHOD_REF, CompletionProposal.TYPE_REF, true);
			collector.setAllowsRequiredProposals(CompletionProposal.METHOD_REF, CompletionProposal.TYPE_IMPORT, true);
			collector.setAllowsRequiredProposals(CompletionProposal.METHOD_REF, CompletionProposal.METHOD_IMPORT, true);

			collector.setAllowsRequiredProposals(CompletionProposal.CONSTRUCTOR_INVOCATION, CompletionProposal.TYPE_REF, true);

			collector.setAllowsRequiredProposals(CompletionProposal.ANONYMOUS_CLASS_CONSTRUCTOR_INVOCATION, CompletionProposal.TYPE_REF, true);
			collector.setAllowsRequiredProposals(CompletionProposal.ANONYMOUS_CLASS_DECLARATION, CompletionProposal.TYPE_REF, true);

			collector.setAllowsRequiredProposals(CompletionProposal.TYPE_REF, CompletionProposal.TYPE_REF, true);
			
			unit.codeComplete(JsonRpcHelpers.toOffset(unit.getBuffer(), line, column), collector, new NullProgressMonitor());
		} catch (JavaModelException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return proposals;

	}
	
	public String computeHover(String uri, int line, int column){
		ICompilationUnit unit = openUnits.get(uri);
		HoverInfoProvider provider = new HoverInfoProvider(unit);
		return provider.computeHover(line,column);
		
	}
	
	public Location computeDefinitonNavigation(String uri, int line, int column) {
		ICompilationUnit unit = openUnits.get(uri);
		
		try {
			IJavaElement[] elements = unit.codeSelect(JsonRpcHelpers.toOffset(unit.getBuffer(), line, column), 0);

			if (elements == null || elements.length != 1)
				return null;
			IJavaElement element = elements[0];
			IResource resource = element.getResource();

			// if the selected element corresponds to a resource in workspace,
			// navigate to it
			if (resource != null && resource.getProject() != null) {
				return getLocation(unit, element);
			}
		} catch (JavaModelException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}


	/**
	 * @param unit
	 * @param element
	 * @param resource
	 * @param $
	 * @throws JavaModelException
	 */
	private Location getLocation(ICompilationUnit unit, IJavaElement element)
			throws JavaModelException {
		Location $ = new Location();
		$.setUri("file://"+ element.getResource().getLocationURI().getPath());
		if (element instanceof ISourceReference) {
			ISourceRange nameRange = ((ISourceReference) element).getSourceRange();
			int[] loc = JsonRpcHelpers.toLine(unit.getBuffer(),nameRange.getOffset());
			int[] endLoc = JsonRpcHelpers.toLine(unit.getBuffer(), nameRange.getOffset() + nameRange.getLength());
			
			if(loc != null){
				$.setLine(loc[0]);
				$.setColumn(loc[1]);
			}
			if(endLoc != null ){
				$.setEndLine(endLoc[0]);
				$.setEndColumn(endLoc[1]);
			}
			return $;
		}
		return null;
	}
	
	public SymbolInformation[] getOutline(String uri){
		ICompilationUnit unit = openUnits.get(uri);
		try {
			IJavaElement[] elements = unit.getChildren();
			ArrayList<SymbolInformation> symbols = new ArrayList<SymbolInformation>(elements.length);
			collectChildren(unit, elements, symbols);
			return symbols.toArray(new SymbolInformation[symbols.size()]);
		} catch (JavaModelException e) {
			e.printStackTrace();
		}
		return new SymbolInformation[0];
	}


	/**
	 * @param unit
	 * @param elements
	 * @param symbols
	 * @throws JavaModelException
	 */
	private void collectChildren(ICompilationUnit unit, IJavaElement[] elements, ArrayList<SymbolInformation> symbols)
			throws JavaModelException {
		for(IJavaElement element : elements ){
			if(element.getElementType() == IJavaElement.TYPE){
				collectChildren(unit, ((IType)element).getChildren(),symbols);
			}
			if(element.getElementType() != IJavaElement.FIELD &&
					element.getElementType() != IJavaElement.METHOD
					){
				continue;
			}
			SymbolInformation si = new SymbolInformation();
			si.setName(element.getElementName());
			si.setKind(SymbolInformation.mapKind(element));
			if(element.getParent() != null )
				si.setContainerName(element.getParent().getElementName());
			si.setLocation(getLocation(unit,element));
			symbols.add(si);
		}
	}

	public boolean isOpen(String uri){
		return openUnits.containsKey(uri);
	}
	
}
