package mikera.matrixx.impl;

import mikera.indexz.Index;
import mikera.matrixx.AMatrix;
import mikera.vectorz.AVector;

/**
 * Matrix that transforms to a subset of elements of the source vector
 * i.e. has exactly one 1.0 in each row
 * 
 * @author Mike
 */
public final class SubsetMatrix extends AMatrix {
	private int dims;
	private Index components;

	private SubsetMatrix(int inputDimensions, Index components) {
		dims=inputDimensions;
		this.components=components;
	}
	
	public static SubsetMatrix create(Index components, int inputDimensions) {
		return new SubsetMatrix(inputDimensions,components);
	}
	
	@Override
	public void transform(AVector source, AVector dest) {
		dest.set(source, components);
	}

	@Override
	public int inputDimensions() {
		return dims;
	}

	@Override
	public int outputDimensions() {
		return components.length();
	}
	
	@Override
	public double elementSum() {
		return rowCount();
	}

	@Override
	public int rowCount() {
		return outputDimensions();
	}

	@Override
	public int columnCount() {
		return inputDimensions();
	}

	@Override
	public double get(int row, int column) {
		return (column==components.get(row))?1.0:0.0;
	}

	@Override
	public void set(int row, int column, double value) {
		throw new UnsupportedOperationException("Can't set matrix values on: "+this.getClass().getName());
	}

	@Override
	public SubsetMatrix exactClone() {
		return SubsetMatrix.create(components.clone(),dims);
	}
}
