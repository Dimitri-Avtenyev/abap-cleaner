package com.sap.adt.abapcleaner.rulehelpers;

import com.sap.adt.abapcleaner.parser.*;
import java.util.*;

public class AlignTable {
	final int maxColumnCount;

	private AlignColumn[] columns;

	private ArrayList<AlignLine> lines = new ArrayList<AlignLine>();
	private HashMap<String, AlignLine> lineDictionary;

	public Token parentToken; // optional, must be set by the using code
	public Token endToken; // optional, must be set by the using code

	boolean canAlignToMonoLine = true;

	/** if this is set, line breaks may be introduced at defined places (AlignCell.overlength...Token), 
	 * if maximum line length would otherwise be exceeded */
	private int maxLineLength = 0;
	
	public final boolean isEmpty() { return (lines.isEmpty()); }

	public final int getLineCount() { return lines.size(); }

	public final Token getFirstToken() { return isEmpty() ? null : lines.get(0).getFirstToken(); }

	public final int getFirstTokenColumnIndex() { return isEmpty() ? -1 : lines.get(0).getFirstTokenColumnIndex(); }

	public final Code getParentCode() { return isEmpty() ? null : getFirstToken().getParentCommand().getParentCode(); }
	
	public final AlignColumn getColumn(int index) {
		return (index >= 0 && index < columns.length) ? columns[index] : null;
	}

	public final AlignLine getLine(int index) {
		return lines.get(index);
	}

	public final AlignLine getLastLine() {
		return lines.isEmpty() ? null : lines.get(lines.size() - 1);
	}

	public final java.lang.Iterable<AlignLine> getLines() { return lines; }

	public AlignTable(int maxColumnCount) {
		this.maxColumnCount = maxColumnCount;
		columns = new AlignColumn[maxColumnCount];
		for (int i = 0; i < maxColumnCount; ++i)
			columns[i] = new AlignColumn(this, i);
	}

	public final AlignLine addLine() {
		AlignLine newLine = new AlignLine(this);
		lines.add(newLine);
		return newLine;
	}

	public final void removeLastLine() {
		if (getLineCount() > 0) {
			removeLineAt(getLineCount() - 1);
		}
	}

	public final void removeLineAt(int index) {
		AlignLine line = lines.get(index);
		for (AlignColumn column : columns) {
			if (line.getCell(column) != null) {
				column.invalidate();
			}
		}
		lines.remove(index);
	}

	public final int getTotalMonoLineWidth() {
		if (!canAlignToMonoLine)
			return getTotalMultiLineWidth();

		int totalResult = 0;
		int result = 0;
		for (AlignColumn column : columns) {
			result += column.getMaxMonoLineWidthWithSpaceLeft(); // returns 0 if the column is empty
			if (column.getForceLineBreakAfter()) {
				totalResult = Math.max(totalResult, result);
				result = 0;
			}
		}
		totalResult = Math.max(totalResult, result);
		return Math.max(totalResult - 1, 0);
	}

	public final int getTotalMultiLineWidth() {
		int totalResult = 0;
		int result = 0;
		for (AlignColumn column : columns) {
			result += column.getMaxMultiLineWidthWithSpaceLeft(); // returns 0 if the column is empty
			if (column.getForceLineBreakAfter()) {
				totalResult = Math.max(totalResult, result);
				result = 0;
			}
		}
		totalResult = Math.max(totalResult, result);
		return Math.max(totalResult - 1, 0);
	}

	public final void overrideWidthOfColumnsFollowedByLineBreaks() {
		// determine columns which are always followed by line breaks and override the cell width in these columns with 1
		for (AlignColumn column : columns) {
			overrideWidthIfColumnIsFollowedByLineBreaks(column);
		}
	}
	
	public final void overrideWidthIfColumnIsFollowedByLineBreaks(AlignColumn column) {
		// determine whether in current code, the column is always followed by line breaks (or is forced to do so); 
		// if so, override the cell width in this columns with 1
		int colIndex = column.getIndex();
		boolean isFollowedByLineBreaks = true;
		if (!column.getForceLineBreakAfter()) {
			for (AlignLine line : lines) {
				if (line.getCell(colIndex) == null) 
					continue;
				// since an AlignCell does not always contain all Tokens (e.g. line-end comments), 
				// search for the next non-empty cell after this cell to check whether it starts with a line break
				AlignCell nextNonEmptyCell = line.getNextNonEmptyCellAfter(colIndex);
				if (nextNonEmptyCell != null && nextNonEmptyCell.getFirstToken().lineBreaks == 0) {
					isFollowedByLineBreaks = false;
					break;
				}
			}
		}
		if (isFollowedByLineBreaks) {
			for (AlignLine line : lines) {
				if (line.getCell(colIndex) != null) 
					line.getCell(colIndex).setOverrideTextWidth(1);
			}				
			// trigger lazy recalculation of maximum width in column
			column.invalidate();
		}
	}
	
	public final Command[] align(int basicIndent, int firstLineBreaks, boolean keepMultiline) {
		return align(basicIndent, firstLineBreaks, keepMultiline, true, false);
	}

	/**
	 * aligns all cells of the table
	 * 
	 * @param basicIndent
	 * @param firstLineBreaks
	 * @param keepMultiline   true = a Term may cover multiple lines; false = put all terms on the same line, replacing line breaks with a space between Tokens 
	 * 							  (only possible if no Term contains line-end comments)
	 * @param condenseInnerSpaces true = also condense spaces inside Terms with 1 space only
	 * @param forceFirstLineBreaks true = force a line break at table start, even if the table continues after opening "("
	 * @return
	 */
	public final Command[] align(int basicIndent, int firstLineBreaks, boolean keepMultiline, boolean condenseInnerSpaces, boolean forceFirstLineBreaks) {
		if (!canAlignToMonoLine)
			keepMultiline = true;

		ArrayList<Command> changedCommands = new ArrayList<Command>();
		Command lastChangedCommand = null;

		// reset the effective indent of all columns
		for (AlignColumn column : columns) {
			column.setEffectiveIndent(0);
		}
		
		// determine whether the table starts with the very first Token in the Code, 
		// as this Token may not have line breaks above it and must get the basicIndent as spacesLeft 
		boolean startsWithFirstTokenInCode = false;
		if (lines.size() > 0) {
			for (AlignColumn column : columns) {
				if (column.isEmpty())
					continue;
				AlignCell cell = lines.get(0).getCell(column);
				if (cell != null) { 
					startsWithFirstTokenInCode = cell.getFirstToken().isFirstTokenInCode();
					break;
				}
			}
		}

		boolean isDdlOrDcl = (getFirstToken() != null && getFirstToken().getParentCommand().isDdlOrDcl());
		int minSpacesLeft = isDdlOrDcl ? 0 : 1;
		boolean isFirstLine = true;
		for (AlignLine line : lines) {
			int lineBreaks = isFirstLine ? firstLineBreaks : 1;
			int spacesLeft = (lineBreaks == 0 && !startsWithFirstTokenInCode) ? minSpacesLeft : basicIndent;
			boolean lineChanged = false;

			// in the special case VALUE or NEW constructors for tables, the AlignTable may contain the assignments for 
			// multiple table rows, which should all continue behind their opening "(", regardless of their previous position  
			// (this also applies for isFirstLine == true)
			Token firstTokenInLine = line.getFirstToken();
			Token prevToken = (firstTokenInLine == null) ? null : firstTokenInLine.getPrev();
			if (forceFirstLineBreaks && isFirstLine && firstLineBreaks > 0) {
				// in the name list of DDIC-based CDS Views, a line break after "(" is intentional
			} else if (firstTokenInLine != null && prevToken != null) { // && !isFirstLine && firstTokenInLine.lineBreaks == 0
				int prevTokenEndIndex = prevToken.getEndIndexInLine(); 
				if (prevTokenEndIndex + 1 == basicIndent) { // even if minSpacesLeft == 0
					lineBreaks = 0;
					spacesLeft = 1;
				} else if (prevTokenEndIndex == basicIndent && minSpacesLeft == 0 && prevToken.textEquals("(")) {
					// DDL might be configured to attach parentheses to their content
					lineBreaks = 0;
					spacesLeft = 0;
				}
			}
			
			int columnIndent = basicIndent;
			for (AlignColumn column : columns) {
				boolean forceMaxIndent = false;
				if (column.getForceIndentOffset() >= 0) {
					AlignColumn baseColumn = column.getForceIndentBaseColumn();
					columnIndent = (baseColumn == null ? basicIndent : baseColumn.getEffectiveIndent()) + column.getForceIndentOffset();
					spacesLeft = columnIndent;
				
				} else if (column.getForceMaxIndent() >= 0) { 
					// If the width of the previous column exceeds the 'forced maximum indent' that was configured for the current column, 
					// switch on 'forceMaxIndent' mode, which will automatically a) move cells to the next line or b) make them continue 
					// on the current line, depending on whether they can (individually) fulfill the 'forced maximum indent'.
					// This is used for DDL select list elements to have a maximum indent of the "as <alias>" column. 
					// Note that a 'forced maximum indent' can NOT be combined with the getForceIndentOffset() concept.
					if (columnIndent > column.getForceMaxIndent()) {
						columnIndent = column.getForceMaxIndent();
						forceMaxIndent = true;
					}
				}

				column.setEffectiveIndent(columnIndent);
				if (column.isEmpty()) {
					if (column.getForceLineBreakAfter()) {
						lineBreaks = 1;
						// spacesLeft can be undefined, because the next column should have a forced indent
					}
					continue;
				}

				AlignCell cell = line.getCell(column);
				int columnWidth = keepMultiline ? column.getMaxMultiLineWidthWithSpaceLeft() : column.getMaxMonoLineWidthWithSpaceLeft();
				if (cell == null) {
					spacesLeft += columnWidth;
					columnIndent += columnWidth;
					continue;
				} 
			
				Token prev = cell.getFirstToken().getPrev();
				if (prev != null && forceMaxIndent) {
					// if a 'forced maximum indent' was configured for the current column, existing lineBreaks are overridden, 
					// depending on whether the cell must (individually) be moved to the next line to fulfill the forced maximum indent:
					if (prev.getEndIndexInLine() >= columnIndent) {
						// force the cell to the next line to fulfill the forced maximum indent
						lineBreaks = 1;
						spacesLeft = columnIndent;
					} else {
						// continue on the same line, because this cell can keep the forced maximum indent 
						lineBreaks = 0;
						spacesLeft = columnIndent - prev.getEndIndexInLine(); 
					} 
				} else if (lineBreaks == 0) {
					if (prev != null && prev.isComment()) {
						// prevent code from being appended to a comment
						lineBreaks = 1;
						spacesLeft = columnIndent;
					
					} else if (!cell.getFirstToken().isFirstTokenInCode()) {
						// ensure there is always at least one space
						spacesLeft = Math.max(spacesLeft, minSpacesLeft);
					}
				} else {
					if (cell.getFirstToken().lineBreaks > lineBreaks) {
						// if there should be a line break, then also keep multiple existing line breaks
						lineBreaks = cell.getFirstToken().lineBreaks;
					}
				}
				
				// if line length would be exceeded, move the 'overlength line break Token' to the next line, aligning it 
				// with one of the 'fallback Tokens' (especially, in declarations, move VALUE below TYPE or the identifier)
				AlignOverlengthAction overlengthAction = null;
				Token lineBreakToken = line.getOverlengthLineBreakToken();
				if (maxLineLength > 0 && lineBreakToken != null && cell.contains(lineBreakToken) && lineBreaks == 0 && prev != null && !column.rightAlign && !keepMultiline) {
					// determine the text width from cell start to the end of this AlignLine (assuming everything is condensed)
					int remainingWidth = cell.getFirstToken().getCondensedWidthUpTo(line.getLastToken(), false);
					
					if (prev.getEndIndexInLine() + spacesLeft + remainingWidth >= maxLineLength) {
						// determine the text width from the 'overlength line break token' to the end of this AlignLine
						int requiredWidth = lineBreakToken.getCondensedWidthUpTo(line.getLastToken(), false);
						overlengthAction = new AlignOverlengthAction(lineBreakToken, line.getOverlengthFallbackToken1(), line.getOverlengthFallbackToken2(), requiredWidth, maxLineLength);

						if (lineBreakToken == cell.getFirstToken()) {
							// directly add a line break at the beginning of this cell, aligning it with the suitable fallback token 
							// (which was already aligned as part of an earlier AlignCell)
							lineBreaks = 1;
							spacesLeft = overlengthAction.getSpacesLeft();
							overlengthAction = null;
						} else {
							// add a line break within(!) the AlignCell, e.g. if a VALUE clause was merged into a TYPE cell;
							// in such a case, AlignCell.overlength... tokens were propagated when the cells were joined;
							// for now, only set the lineBreak; spacesLeft will be set later in cell.setWhitespace(), 
							// because the position of the fallback tokens is not yet determined
							if (lineBreakToken.setWhitespace(1, lineBreakToken.spacesLeft)) {
								lineChanged = true;
							}
						}
					}
				}
				
				int usedWidth;
				if (column.rightAlign) {
					int cellWidth = keepMultiline ? cell.getMultiLineWidth() : cell.getMonoLineWidth();
					spacesLeft += (columnWidth - 1 - cellWidth); // columnWidth includes 1 space separating it from the next column
					if (cell.setWhitespace(lineBreaks, spacesLeft, keepMultiline, condenseInnerSpaces, overlengthAction))
						lineChanged = true;
					usedWidth = columnWidth - 1;
				} else {
					if (cell.setWhitespace(lineBreaks, spacesLeft, keepMultiline, condenseInnerSpaces, overlengthAction))
						lineChanged = true;
					// 'used width' gets the actual width, independent of AlignCell.overrideTextWidth 
					// (which is e.g. used for 'TYPE ... TABLE ...' cells in AlignDeclarationsRule)
					usedWidth = keepMultiline ? cell.getActualMultiLineWidth() : cell.getActualMonoLineWidth(); 
				}
				
				if (column.getForceLineBreakAfter()) {
					lineBreaks = 1;
					spacesLeft = cell.getStartIndexInFirstLine() + columnWidth; 
				} else {
					lineBreaks = 0;
					spacesLeft = columnWidth - usedWidth;
				}
				columnIndent += columnWidth;
			}
			if (lineChanged) {
				Command command = line.getFirstToken().getParentCommand();
				if (command != lastChangedCommand) {
					changedCommands.add(command);
					lastChangedCommand = command;
				}
			}

			isFirstLine = false;
		}
		return changedCommands.toArray(new Command[0]);
	}

	public final HashMap<String, AlignLine> getLineDictionary() {
		if (lineDictionary == null) {
			lineDictionary = new HashMap<String, AlignLine>();
			for (AlignLine line : lines)
				lineDictionary.put(line.getSimplifiedText(), line);
		}
		return lineDictionary;
	}

	public final AlignLine getLineBySimplifiedText(String simplifiedText) {
      if (getLineDictionary().containsKey(simplifiedText))
         return getLineDictionary().get(simplifiedText);
      else
         return null;
   }

	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		for (AlignLine line : lines) {
			sb.append(line.getSimplifiedText());
			sb.append(System.lineSeparator());
		}
		return sb.toString();
	}

	public void removeAllLinesOfParent(Token parentToken) {
		for (int i = lines.size() - 1; i >= 0; --i) {
			AlignLine line = lines.get(i);
			Token firstToken = line.getFirstToken();
			if (firstToken == null || firstToken.getParent() == parentToken) {
				removeLineAt(i);
			}
		}
	}
	
	public void removeAllLinesWithOutCellIn(int columnIndex) {
		for (int i = lines.size() - 1; i >= 0; --i) {
			AlignLine line = lines.get(i);
			if (line.getCell(columnIndex) == null) {
				removeLineAt(i);
			}
		}
	}
	
	public final void setMaxLineLength(int maxLineLength) {
		this.maxLineLength = maxLineLength;
	}
	
	public int getIndexOfLine(AlignLine line) {
		return lines.indexOf(line);
	}
}
