/*
 * Copyright (c) 2009-2012, Peter Abeles. All Rights Reserved.
 *
 * This file is part of Efficient Java Matrix Library (EJML).
 *
 * EJML is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * EJML is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EJML.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.ejml.alg.dense.decomposition.svd;

import org.ejml.alg.dense.decomposition.bidiagonal.BidiagonalDecomposition;
import org.ejml.alg.dense.decomposition.bidiagonal.BidiagonalDecompositionRow;
import org.ejml.alg.dense.decomposition.bidiagonal.BidiagonalDecompositionTall;
import org.ejml.alg.dense.decomposition.svd.implicitqr.SvdImplicitQrAlgorithm;
import org.ejml.data.DenseMatrix64F;
import org.ejml.factory.SingularValueDecomposition;
import org.ejml.ops.CommonOps;


/**
 * <p>
 * Computes the Singular value decomposition of a matrix using the implicit QR algorithm
 * for singular value decomposition.  It works by first by transforming the matrix
 * to a bidiagonal A=U*B*V<sup>T</sup> form, then it implicitly computing the eigenvalues of the B<sup>T</sup>B matrix,
 * which are the same as the singular values in the original A matrix.
 * </p>
 *
 * <p>
 * Based off of the description provided in:<br>
 * <br>
 * David S. Watkins, "Fundamentals of Matrix Computations," Second Edition. Page 404-411
 * </p>
 *
 * @author Peter Abeles
 */
public class SvdImplicitQrDecompose implements SingularValueDecomposition<DenseMatrix64F> {

    private int numRows;
    private int numCols;

    // dimensions of transposed matrix
    private int numRowsT;
    private int numColsT;

    // if true then it can use the special Bidiagonal decomposition
    private boolean canUseTallBidiagonal;

    // If U is not being computed and the input matrix is 'tall' then a special bidiagonal decomposition
    // can be used which is faster.
    private BidiagonalDecomposition<DenseMatrix64F> bidiag;
    private SvdImplicitQrAlgorithm qralg = new SvdImplicitQrAlgorithm();

    double diag[];
    double off[];

    private DenseMatrix64F Ut;
    private DenseMatrix64F Vt;

    private double singularValues[];
    private int numSingular;

    // compute a compact SVD
    private boolean compact;
    // What is actually computed
    private boolean computeU;
    private boolean computeV;

    // What the user requested to be computed
    // If the transpose is computed instead then what is actually computed is swapped
    private boolean prefComputeU;
    private boolean prefComputeV;

    // Should it compute the transpose instead
    private boolean transposed;

    // Either a copy of the input matrix or a copy of it transposed
    private DenseMatrix64F A_mod = new DenseMatrix64F(1,1);

    /**
     * Configures the class
     *
     * @param compact Compute a compact SVD
     * @param computeU If true it will compute the U matrix
     * @param computeV If true it will compute the V matrix
     * @param canUseTallBidiagonal If true then it can choose to use a tall Bidiagonal decomposition to improve runtime performance.
     */
    public SvdImplicitQrDecompose(boolean compact, boolean computeU, boolean computeV,
                                  boolean canUseTallBidiagonal )
    {
        this.compact = compact;
        this.prefComputeU = computeU;
        this.prefComputeV = computeV;
        this.canUseTallBidiagonal = canUseTallBidiagonal;
    }

    @Override
    public double[] getSingularValues() {
        return singularValues;
    }

    @Override
    public int numberOfSingularValues() {
        return numSingular;
    }

    @Override
    public boolean isCompact() {
        return compact;
    }

    @Override
    public DenseMatrix64F getU( DenseMatrix64F U , boolean transpose) {
        if( !prefComputeU )
            throw new IllegalArgumentException("As requested U was not computed.");
        if( transpose ) {
            if( U == null )
                return Ut;
            else if( U.rowCount() != Ut.rowCount() || U.columnCount() != Ut.columnCount() )
                throw new IllegalArgumentException("Unexpected shape of U");

            U.set(Ut);
        } else {
            if( U == null )
                U = new DenseMatrix64F(Ut.columnCount(),Ut.rowCount());
            else if( U.rowCount() != Ut.columnCount() || U.columnCount() != Ut.rowCount() )
                throw new IllegalArgumentException("Unexpected shape of U");

            CommonOps.transpose(Ut,U);
        }

        return U;
    }

    @Override
    public DenseMatrix64F getV( DenseMatrix64F V , boolean transpose ) {
        if( !prefComputeV )
            throw new IllegalArgumentException("As requested V was not computed.");
        if( transpose ) {
            if( V == null )
                return Vt;
            else if( V.rowCount() != Vt.rowCount() || V.columnCount() != Vt.columnCount() )
                throw new IllegalArgumentException("Unexpected shape of V");

            V.set(Vt);
        } else {
            if( V == null )
                V = new DenseMatrix64F(Vt.columnCount(),Vt.rowCount());
            else if( V.rowCount() != Vt.columnCount() || V.columnCount() != Vt.rowCount() )
                throw new IllegalArgumentException("Unexpected shape of V");
            CommonOps.transpose(Vt,V);
        }

        return V;
    }

    @Override
    public DenseMatrix64F getW( DenseMatrix64F W ) {
        int m = compact ? numSingular : numRows;
        int n = compact ? numSingular : numCols;

        if( W == null )
            W = new DenseMatrix64F(m,n);
        else {
            W.reshape(m,n, false);
            W.zero();
        }

        for( int i = 0; i < numSingular; i++ ) {
            W.unsafeSet(i,i, singularValues[i]);
        }

        return W;
    }

    @Override
    public boolean decompose(DenseMatrix64F orig) {
         setup(orig);

        if (bidiagonalization(orig))
            return false;

        if( computeUWV() )
            return false;

        // make sure all the singular values or positive
        makeSingularPositive();

        // if transposed undo the transposition
        undoTranspose();

        return true;
    }

    @Override
    public boolean inputModified() {
        return false;
    }

    private boolean bidiagonalization(DenseMatrix64F orig) {
        // change the matrix to bidiagonal form
        if( transposed ) {
            A_mod.reshape(orig.columnCount(),orig.rowCount(),false);
            CommonOps.transpose(orig,A_mod);
        } else {
            A_mod.reshape(orig.rowCount(),orig.columnCount(),false);
            A_mod.set(orig);
        }
        return !bidiag.decompose(A_mod);
    }

    /**
     * If the transpose was computed instead do some additional computations
     */
    private void undoTranspose() {
        if( transposed ) {
            DenseMatrix64F temp = Vt;
            Vt = Ut;
            Ut = temp;
        }
    }

    /**
     * Compute singular values and U and V at the same time
     */
    private boolean computeUWV() {
        bidiag.getDiagonal(diag,off);
        qralg.setMatrix(numRowsT,numColsT,diag,off);

//        long pointA = System.currentTimeMillis();
        // compute U and V matrices
        if( computeU )
            Ut = bidiag.getU(Ut,true,compact);
        if( computeV )
            Vt = bidiag.getV(Vt,true,compact);

        qralg.setFastValues(false);
        if( computeU )
            qralg.setUt(Ut);
        else
            qralg.setUt(null);
        if( computeV )
            qralg.setVt(Vt);
        else
            qralg.setVt(null);

//        long pointB = System.currentTimeMillis();

        boolean ret = !qralg.process();

//        long pointC = System.currentTimeMillis();
//        System.out.println("  compute UV "+(pointB-pointA)+"  QR = "+(pointC-pointB));

        return ret;
    }

    private void setup(DenseMatrix64F orig) {
        transposed = orig.columnCount() > orig.rowCount();

        // flag what should be computed and what should not be computed
        if( transposed ) {
            computeU = prefComputeV;
            computeV = prefComputeU;
            numRowsT = orig.columnCount();
            numColsT = orig.rowCount();
        } else {
            computeU = prefComputeU;
            computeV = prefComputeV;
            numRowsT = orig.rowCount();
            numColsT = orig.columnCount();
        }

        numRows = orig.rowCount();
        numCols = orig.columnCount();

        if( diag == null || diag.length < numColsT ) {
            diag = new double[ numColsT ];
            off = new double[ numColsT-1 ];
        }

        // if it is a tall matrix and U is not needed then there is faster decomposition algorithm
        if( canUseTallBidiagonal && numRows > numCols * 2 && !computeU ) {
            if( bidiag == null || !(bidiag instanceof BidiagonalDecompositionTall) ) {
                bidiag = new BidiagonalDecompositionTall();
            }
        } else if( bidiag == null || !(bidiag instanceof BidiagonalDecompositionRow) ) {
            bidiag = new BidiagonalDecompositionRow();
        }
    }

    /**
     * With the QR algorithm it is possible for the found singular values to be negative.  This
     * makes them all positive by multiplying it by a diagonal matrix that has
     */
    private void makeSingularPositive() {
        numSingular = qralg.getNumberOfSingularValues();
        singularValues = qralg.getSingularValues();

        for( int i = 0; i < numSingular; i++ ) {
            double val = qralg.getSingularValue(i);

            if( val < 0 ) {
                singularValues[i] = 0.0d - val;

                if( computeU ) {
                    // compute the results of multiplying it by an element of -1 at this location in
                    // a diagonal matrix.
                    int start = i* Ut.columnCount();
                    int stop = start+ Ut.columnCount();

                    for( int j = start; j < stop; j++ ) {
                        Ut.set(j, 0.0d - Ut.get(j));
                    }
                }
            } else {
                singularValues[i] = val;
            }
        }
    }

    @Override
    public int numRows() {
        return numRows;
    }

    @Override
    public int numCols() {
        return numCols;
    }
}