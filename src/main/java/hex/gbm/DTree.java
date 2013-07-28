package hex.gbm;

import java.util.ArrayList;
import java.util.Arrays;
import water.*;
import water.H2O.H2OCountedCompleter;
import water.fvec.*;
import water.util.Log;
import water.util.Log.Tag.Sys;

/**
   A Decision Tree, laid over a Frame of Vecs, and built distributed.

   This class defines an explicit Tree structure, as a collection of {@code
   Tree} {@code Node}s.  The Nodes are numbered with a unique {@code _nid}.
   Users need to maintain their own mapping from their data to a {@code _nid},
   where the obvious technique is to have a Vec of {@code _nid}s (ints), one
   per each element of the data Vecs.

   Each {@code Node} has a {@code Histogram}, describing summary data about the
   rows.  The Histogram requires a pass over the data to be filled in, and we
   expect to fill in all rows for Nodes at the same depth at the same time.
   i.e., a single pass over the data will fill in all leaf Nodes' Histograms
   at once.

   @author Cliff Click
*/

class DTree extends Iced {
  final String[] _names; // Column names
  private Node[] _ns;    // All the nodes in the tree.  Node 0 is the root.
  int _len;              // Resizable array
  DTree( String[] names ) { _names = names; _ns = new Node[1]; }

  public final Node root() { return _ns[0]; }

  // Return Node i
  public final Node node( int i ) { 
    if( i >= _len ) throw new ArrayIndexOutOfBoundsException(i); 
    return _ns[i]; 
  }
  public final UndecidedNode undecided( int i ) { return (UndecidedNode)node(i); }
  public final   DecidedNode   decided( int i ) { return (  DecidedNode)node(i); }

  // Get a new node index, growing innards on demand
  private int newIdx() {
    if( _len == _ns.length ) _ns = Arrays.copyOf(_ns,_len<<1);
    return _len++;
  }

  // Abstract node flavor
  static abstract class Node extends Iced {
    transient DTree _tree;
    final int _pid;             // Parent node id, root has no parent and uses -1
    final int _nid;             // My node-ID, 0 is root
    Node( DTree tree, int pid, int nid ) { 
      _tree = tree; 
      _pid=pid;
      tree._ns[_nid=nid] = this;
    }

    // Recursively print the decision-line from tree root to this child.
    StringBuilder printLine(StringBuilder sb ) {
      if( _pid==-1 ) return sb.append("[root]");
      DecidedNode parent = _tree.decided(_pid);
      parent.printLine(sb).append(" to ");
      return parent.printChild(sb,_nid);
    }
  }

  // An UndecidedNode: Has a Histogram which is filled in (in parallel with other
  // histograms) in a single pass over the data.  Does not contain any
  // split-decision.
  static class UndecidedNode extends Node {
    Histogram _hs[];            // Histograms per column
    UndecidedNode( DTree tree, int pid, Histogram hs[] ) { super(tree,pid,tree.newIdx()); _hs=hs; }

    @Override public String toString() {
      final String colPad="  ";
      final int cntW=4, mmmW=4, varW=4;
      final int colW=cntW+1+mmmW+1+mmmW+1+mmmW+1+varW;
      StringBuilder sb = new StringBuilder();
      sb.append("Nid# ").append(_nid).append(", ");
      printLine(sb).append("\n");
      int numClasses = 0;       // Assume Regression
      final int ncols = _hs.length;
      for( int j=0; j<ncols; j++ )
        if( _hs[j] != null ) {
          p(sb,_hs[j]._name+String.format(", err=%5.2f",_hs[j].score()),colW).append(colPad);
          if( _hs[j]._clss != null ) numClasses = _hs[j]._clss[0].length; // Classification
        }
      sb.append('\n');
      for( int j=0; j<ncols; j++ ) {
        if( _hs[j] == null ) continue;
        p(sb,"cnt" ,cntW).append('/');
        p(sb,"min" ,mmmW).append('/');
        p(sb,"max" ,mmmW).append('/');
        if( numClasses == 0 ) {
          p(sb,"mean",mmmW).append('/');
          p(sb,"var" ,varW).append(colPad);
        } else {
          p(sb,"C0",mmmW).append('-');
          p(sb,"C"+(numClasses-1),varW).append(colPad);
        }
      }
      sb.append('\n');
      for( int i=0; i<Histogram.BINS; i++ ) {
        for( int j=0; j<ncols; j++ ) {
          if( _hs[j] == null ) continue;
          if( i < _hs[j]._bins.length ) {
            p(sb,Long.toString(_hs[j]._bins[i]),cntW).append('/');
            p(sb,              _hs[j]._mins[i] ,mmmW).append('/');
            p(sb,              _hs[j]._maxs[i] ,mmmW).append('/');
            if( numClasses==0 ) { // Regression
              p(sb,              _hs[j]. mean(i) ,mmmW).append('/');
              p(sb,              _hs[j]. var (i) ,varW).append(colPad);
            } else {            // Classification
              StringBuilder sb2 = new StringBuilder();
              long N = _hs[j]._bins[i];
              long cls[] = _hs[j]._clss[i];
              for( int k = 0; k<cls.length; k++ )
                sb2.append(cls[k]).append(',');
              p(sb,sb2.toString(),mmmW+1+varW).append(colPad);
            }
          } else {
            p(sb,"",colW).append(colPad);
          }
        }
        sb.append('\n');
      }
      sb.append("Nid# ").append(_nid);
      return sb.toString();
    }
    static private StringBuilder p(StringBuilder sb, String s, int w) {
      return sb.append(Log.fixedLength(s,w));
    }
    static private StringBuilder p(StringBuilder sb, double d, int w) {
      String s = Double.isNaN(d) ? "NaN" :
        ((d==Double.MAX_VALUE || d==-Double.MAX_VALUE) ? " -" : 
         Double.toString(d));
      if( s.length() <= w ) return p(sb,s,w);
      s = String.format("%4.1f",d);
      if( s.length() > w )
        s = String.format("%4.0f",d);
      return sb.append(s);
    }
  }

  // Internal tree nodes which split into several children over a single
  // column.  Includes a split-decision: which child does this Row belong to?
  // Does not contain a histogram describing how the decision was made.
  static abstract class DecidedNode extends Node {
    final int _col;             // Column we split over
    final double _min, _step;   // Binning info of column
    // The following arrays are all based on a bin# extracted from linear
    // interpolation of _col, _min and _step.

    // For classification, we return a zero-based class, and the prediction is
    // a value from zero to 1 describing how weak our guess is.  For instance,
    // during tree-building we decide to build a DecidedNode from 10 rows; 8
    // rows are class A, and 2 rows are class B.  Then our class is A, but our
    // error is 0.2 (2 out of 10 wrong).
    final int    _ns[];            // An n-way split node
    final double _mins[], _maxs[]; // Hang onto for printing purposes
    // For Regressions:
    //  ycls[x][0] == count of rows for this leaf
    //  pred[x] == split X's prediction, typically mean of response variable
    // For Classifications:
    //  ycls[x][] == Class distribution of leaves
    final long   _ycls[/*split*/][/*class*/]; // Class distribution
    final double _pred[/*split*/]; // Regression: this is the prediction

    // Pick the best column from the given histograms
    abstract int bestCol( Histogram[] hs );

    DecidedNode( UndecidedNode n ) {
      super(n._tree,n._pid,n._nid); // Replace Undecided with this DecidedNode
      _col = bestCol(n._hs);        // Best split-point for this tree
      // From the splitting Undecided, get the column, min, max
      Histogram splitH = n._hs[_col];// Histogram of the column being split
      int nums = splitH._nbins;      // Number of split choices
      long clss[][] = splitH._clss;  // Class histogram
      assert nums > 1;          // Should always be some bins to split between
      _min  = splitH._min ;     // Binning info
      _step = splitH._step;
      assert _step > 0;
      _mins = splitH._mins;     // Hang onto for printing purposes
      _maxs = splitH._maxs;     // Hang onto for printing purposes
      _ns = new int[nums];
      _ycls = (clss==null) ? new long[nums][] : clss;
      _pred = new double[nums];
      int ncols = _tree._names.length-1; // ncols: all columns, minus response
      for( int i=0; i<nums; i++ ) { // For all split-points
        // Setup for children splits
        Histogram nhists[] = splitH.split(_col,i,n._hs,_tree._names,ncols);
        _ns[i] = nhists == null ? -1 : new UndecidedNode(_tree,_nid,nhists)._nid;
        // Also setup predictions locally
        if( clss == null ) {                      // Regression?
          _ycls[i] = new long[]{splitH._bins[i]}; // Number of entries in bin
          _pred[i] = splitH. mean(i);             // Prediction is mean of bin
        }
      }
    }

    // Bin #.
    public int bin( Chunk chks[], int i ) {
      double d = chks[_col].at0(i);         // Value to split on for this row
      int idx1 = (int)((d-_min)/_step);     // Interpolate bin#
      assert idx1 >= 0;                     // Expect sanity
      int bin = Math.min(idx1,_ns.length-1);// Cap at length
      return bin;
    }

    public int ns( Chunk chks[], int i ) { return _ns[bin(chks,i)]; }

    @Override public String toString() {
      throw H2O.unimpl();
    }

    StringBuilder printChild( StringBuilder sb, int nid ) {
      for( int i=0; i<_ns.length; i++ ) 
        if( _ns[i]==nid ) 
          return sb.append("[").append(_mins[i]).append(" <= ").
            append(_tree._names[_col]).append(" <= ").append(_maxs[i]).append("]");
      throw H2O.fail();
    }    
  }


  // --------------------------------------------------------------------------
  // Fuse 2 conceptual passes into one:
  //
  // Pass 1: Score a prior partially-built tree model, and make new Node
  //         assignments to every row.  This involves pulling out the current
  //         assigned DecidedNode, "scoring" the row against that Node's
  //         decision criteria, and assigning the row to a new child
  //         UndecidedNode (and giving it an improved prediction).
  //
  // Pass 2: Build new summary Histograms on the new child UndecidedNodes every
  //         row got assigned into.  Collect counts, mean, variance, min, max
  //         per bin, per column.
  //
  // The result is a set of Histogram arrays; one Histogram array for each
  // unique 'leaf' in the tree being histogramed in parallel.  These have node
  // ID's (nids) from 'leaf' to 'tree._len'.  Each Histogram array is for all
  // the columns in that 'leaf'.
  //
  // The other result is a prediction "score" for the whole dataset, based on
  // the previous passes' Histograms.
  static class ScoreBuildHistogram extends MRTask2<ScoreBuildHistogram> {
    final DTree _trees[]; // Read-only, shared (except at the histograms in the Nodes)
    final int   _leafs[]; // Number of active leaves (per tree)
    final int _ncols;
    final int _numClasses;      // Zero for regression, else #classes
    // Bias classes to zero; e.g. covtype classes range from 1-7 so this is 1.
    // e.g. prostate classes range 0-1 so this is 0
    final int _ymin;
    Histogram _hcs[/*tree id*/][/*tree-relative node-id*/][/*column*/];
    ScoreBuildHistogram(DTree trees[], int leafs[], int ncols, int numClasses, int ymin) { 
      _trees=trees; 
      _leafs=leafs; 
      _ncols=ncols; 
      _numClasses = numClasses; 
      _ymin = ymin;
    }

    public Histogram[] getFinalHisto( int tid, int nid ) {
      Histogram hs[] = _hcs[tid][nid-_leafs[tid]];
      // Having gather min/max/mean/class/etc on all the data, we can now
      // tighten the min & max numbers.
      for( int j=0; j<hs.length; j++ ) {
        Histogram h = hs[j];    // Old histogram of column
        if( h != null ) h.tightenMinMax();
      }
      return hs;
    }

    @Override public void map( Chunk[] chks ) {
      assert _ncols+1/*response variable*/+_trees.length == chks.length 
        : "Missing columns?  ncols="+_ncols+", 1 for response, ntrees="+_trees.length+", and found "+chks.length+" vecs";
      Chunk ys   = chks[_ncols];

      // We need private (local) space to gather the histograms.
      // Make local clones of all the histograms that appear in this chunk.
      _hcs = new Histogram[_trees.length][][];

      // For all trees
      for( int t=0; t<_trees.length; t++ ) {
        DTree tree = _trees[t];
        int leaf = _leafs[t];
        Histogram hcs[][] = _hcs[t] = new Histogram[tree._len-leaf][]; // A leaf-biased array of all active histograms
        Chunk nids = chks[_ncols+1/*response col*/+t];

        // Pass 1 & 2
        for( int i=0; i<nids._len; i++ ) {
          int nid = (int)nids.at80(i);       // Get Node to decide from
          if( nid==-1 ) continue;            // row already predicts perfectly
          
          // Pass 1: Score row against current decisions & assign new split
          if( leaf > 0 )   // Prior pass exists?
            nids.set80(i,nid = tree.decided(nid).ns(chks,i));
          
          // Pass 1.9
          if( nid==-1 ) continue;         // row already predicts perfectly
          
          // We need private (local) space to gather the histograms.
          // Make local clones of all the histograms that appear in this chunk.
          Histogram nhs[] = hcs[nid-leaf];
          if( nhs == null ) {     // Lazily manifest this histogram for 'nid'
            nhs = hcs[nid-leaf] = new Histogram[_ncols];
            Histogram ohs[] = tree.undecided(nid)._hs; // The existing column of Histograms
            for( int j=0; j<_ncols; j++ )       // Make private copies
              if( ohs[j] != null )
                nhs[j] = ohs[j].copy(_numClasses);
          }
          
          // Pass 2
          // Bump the local histogram counts
          if( _numClasses == 0 ) { // Regression?
            double y = ys.at0(i);
            for( int j=0; j<_ncols; j++) // For all columns
              if( nhs[j] != null ) // Some columns are ignored, since already split to death
                nhs[j].incr(chks[j].at0(i),y);
          } else {                // Classification
            int ycls = (int)ys.at80(i) - _ymin;
            for( int j=0; j<_ncols; j++) // For all columns
              if( nhs[j] != null ) // Some columns are ignored, since already split to death
                nhs[j].incr(chks[j].at0(i),ycls);
          }
        }
      }
    }

    @Override public void reduce( ScoreBuildHistogram sbh ) {
      // Merge histograms
      for( int t=0; t<_hcs.length; t++ ) {
        Histogram hcs[][] = _hcs[t];
        for( int i=0; i<hcs.length; i++ ) {
          Histogram hs1[] = hcs[i], hs2[] = sbh._hcs[t][i];
          if( hs1 == null ) hcs[i] = hs2;
          else if( hs2 != null )
            for( int j=0; j<hs1.length; j++ )
              if( hs1[j] == null ) hs1[j] = hs2[j];
              else if( hs2[j] != null )
                hs1[j].add(hs2[j]);
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // Compute sum-squared-error.  Should use the recursive-mean technique.
  public static class BulkScore extends MRTask2<BulkScore> {
    final DTree _trees[]; // Read-only, shared (except at the histograms in the Nodes)
    final int _ncols;     // Number of columns actively being worked
    final int _numClasses;      // Zero for regression, else #classes
    // Bias classes to zero; e.g. covtype classes range from 1-7 so this is 1.
    // e.g. prostate classes range 0-1 so this is 0
    final int _ymin;
    double _sum;
    long _err;
    BulkScore( DTree trees[], int ncols, int numClasses, int ymin ) { _trees = trees; _ncols = ncols; _numClasses = numClasses; _ymin = ymin; }
    @Override public void map( Chunk chks[] ) {
      assert _ncols+1/*response variable*/+_trees.length == chks.length 
        : "Missing columns?  ncols="+_ncols+", 1 for response, ntrees="+_trees.length+", and found "+chks.length+" vecs";
      Chunk ys = chks[_ncols];
      long clss[] = new long[_numClasses]; // Shared array for computing classes
      for( int i=0; i<ys._len; i++ ) {
        double err = score0( chks, i, ys.at0(i), clss );
        _sum += err*err;        // Squared error
      }
    }
    @Override public void reduce( BulkScore t ) { _sum += t._sum; _err += t._err; }

    // Return a relative error.  For regression it's y-mean.  For classification, 
    // it's the %-tage of the response class out of all rows in the leaf, plus
    // a count of absolute errors when we predict the majority class.
    private double score0( Chunk chks[], int i, double y, long clss[] ) {
      double sum=0;             // Regression: average across trees
      long rows=0;
      if( _numClasses > 0 )     // Classification: zero class distribution
        Arrays.fill(clss,0);
      // For all trees
      for( int t=0; t<_trees.length; t++ ) {
        final DTree tree = _trees[t];
        // "score" this row on this tree.  Apply the tree decisions at each
        // point, walking down the tree to a leaf.
        DecidedNode prev = null;
        Node node = tree.root();
        while( node instanceof DecidedNode ) { // While tree-walking
          prev = (DecidedNode)node;
          int nid = prev.ns(chks,i);
          if( nid == -1 ) break;
          node = tree.node(nid);
        }
        // We hit the end of the tree walk.  Get this tree's prediction
        int bin = prev.bin(chks,i);    // Which bin did we decide on?
        long[] ycls = prev._ycls[bin]; // Classes for that bin
        if( _numClasses == 0 ) {       // Regression?
          long num = ycls[0];          // "classes" is really just bin-count
          rows += num;                 // More total rows
          sum  += prev._pred[bin]*num; // More total regression count
        } else {                // Classification?
          for( int c=0; c<_numClasses; c++ )
            clss[c] += ycls[c]; // Compute distribution
        }
      }

      if( _numClasses == 0 ) {
        double prediction = sum/rows; // Average of trees is prediction
        return prediction - y;        // Error
      } else {
        rows += clss[0];        // Find total rows
        int best=0;             // Find largest class across all trees
        for( int c=1; c<_numClasses; c++ ) {
          rows += clss[c];
          if( clss[c] > clss[best] ) best=c;
        }
        int ycls = (int)y-_ymin;   // Zero-based response class
        if( best != ycls ) _err++; // Absolute prediction error
        return (double)(rows-clss[best])/rows; // Error
      }
    }

    public void report( Sys tag, long nrows, int depth ) {
      int lcnt=0;
      for( int t=0; t<_trees.length; t++ ) lcnt += _trees[t]._len;
      Log.info(tag,"============================================================== ");
      Log.info(tag,"Average squared prediction error for tree of depth "+depth+" is "+(_sum/nrows));
      Log.info(tag,"Total of "+_err+" errors on "+nrows+" rows, with "+_trees.length+" trees (average of "+((double)lcnt/_trees.length)+" nodes)");
    }
  }
}
