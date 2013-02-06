package embedded;

import java.util.*;
import java.io.*;

/**
 *  A Sample Main Class which embeddeds the H2O Scoring Engine
 *
 *  The scoring engine is a modular, seperable piece of H2O intended to be
 *  embedded into a larger user application.  It understands how to load and
 *  score all H2O models, but cannot do modeling by itself.
 *
 *  Compile:   javac -cp ".;h2o.jar" ScoreMain.java
 *  Run:       java  -cp ".;h2o.jar" ScoreMain
 */
class ScoreMain {
  static HashMap<String, Comparable> ROW;

  public static void main(String[] args) throws Exception {
    // Prep the app; get data available
    sampleAppInit(args);

    // Load a PMML model
    System.out.println("Loading model to score");
    FileInputStream fis = new FileInputStream("../../../demo/SampleScorecard.pmml");
    water.score.ScorecardModel scm = water.parser.PMMLParser.load(fis);
    
    
    sampleAppDoesStuff(scm);

    System.out.println("Sample App Shuts Down");
    System.exit(0);
  }

  public static void sampleAppInit(String[] args) throws Exception {
    System.out.println("Sample App Init Code Goes Here; loading data to score");

    // Make data available.  In this case, parse a simple text file and inject
    // pairs into a HashMap.
    File f = new File("../../../demo/SampleData.txt");
    String text = new Scanner( f ).useDelimiter("\\A").next();
    ROW = new HashMap<String, Comparable>();
    String[] toks = text.split("[,\\{\\}]");
    for( String tok : toks ) {
      if( tok.length() > 0 ) {
        String pair[] = tok.split("[=:]");
        if( pair.length==2 ) {
          ROW.put(trim(pair[0]),asNum(trim(pair[1])));
        }
      }
    }
  }

  static String trim( String x ) { return x.trim().replaceAll("\"",""); }
  static Comparable asNum( String x ) {
    if( "true" .equals(x) ) return new Boolean(true );
    if( "false".equals(x) ) return new Boolean(false);
    try { return new Long   (Long  .valueOf(x)); }
    catch( NumberFormatException nfe ) { }
    try { return new Double (Double.valueOf(x)); }
    catch( NumberFormatException nfe ) { }
    return x;
  }

  public static void sampleAppDoesStuff(water.score.ScorecardModel scm) {

    System.out.println("Initial score="+scm.score(ROW));
    for( int i=0; i<10000; i++ )
      scm.score(ROW);
    long start = System.currentTimeMillis();
    final int ITER=100000;
    for( int i=0; i<ITER; i++ )
      scm.score(ROW);
    long now = System.currentTimeMillis();
    long delta = now-start;
    System.out.println(""+ITER+" in "+delta+"ms = "+((double)delta*1000*1000/ITER)+" microsec/score");

  }
}
