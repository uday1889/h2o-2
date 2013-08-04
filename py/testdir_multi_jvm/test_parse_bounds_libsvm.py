import unittest
import random, sys, time, os
sys.path.extend(['.','..','py'])

import h2o, h2o_cmd, h2o_hosts, h2o_browse as h2b, h2o_import as h2i
CHECK_MAX = False

def write_syn_dataset(csvPathname, rowCount, colCount, SEEDPERFILE):
    # we can do all sorts of methods off the r object
    r = random.Random(SEEDPERFILE)

    def addValToRowStuff(colNumber, val, rowData, synColSumDict):
        if val!=0:
            rowData.append(str(colNumber) + ":" + str(val)) # f should always return string
            if colNumber in synColSumDict:
                synColSumDict[colNumber] += val # sum of column (dict)
            else:
                synColSumDict[colNumber] = val # sum of column (dict)
    dsf = open(csvPathname, "w+")

    synColSumDict = {0: 0} # guaranteed to have col 0 for output
    for i in range(rowCount):
        rowData = []

        if i==(rowCount-1): # last row
            val = 1
            colNumber = colCount # max
            colNumberMax = colNumber
        else:
            # 50%
            d = random.randint(0,1)
            if d==1:
                val = 1
            else:
                val = 0
            colNumber = 1 # always make it col 1

        addValToRowStuff(colNumber, val, rowData, synColSumDict)

        # add output
        val = 0
        rowData.insert(0, val)
        synColSumDict[0] += val # sum of column (dict)

        rowDataCsv = " ".join(map(str,rowData))
        dsf.write(rowDataCsv + "\n")

    dsf.close()
    return (colNumberMax, synColSumDict)

class Basic(unittest.TestCase):
    def tearDown(self):
        h2o.check_sandbox_for_errors()

    @classmethod
    def setUpClass(cls):
        global SEED, localhost
        SEED = h2o.setup_random_seed()
        localhost = h2o.decide_if_localhost()
        if (localhost):
            h2o.build_cloud(2,java_heap_GB=5)
        else:
            h2o_hosts.build_cloud_with_hosts()

    @classmethod
    def tearDownClass(cls):
        h2o.tear_down_cloud()

    def test_parse_bounds_libsvm (self):
        print "Random 0/1 for col1. Last has max col = 1, All have zeros for class."
        h2b.browseTheCloud()
        SYNDATASETS_DIR = h2o.make_syn_dir()
        tryList = [
            (100, 100, 'cA', 300),
            (100000, 100, 'cB', 300),
            (100, 100000, 'cC', 300),
            ]

        # h2b.browseTheCloud()
        for (rowCount, colCount, key2, timeoutSecs) in tryList:
                SEEDPERFILE = random.randint(0, sys.maxint)
                csvFilename = "syn_%s_%s_%s.csv" % (SEEDPERFILE, rowCount, colCount)
                csvPathname = SYNDATASETS_DIR + '/' + csvFilename

                print "Creating random", csvPathname
                # dict of col sums for comparison to exec col sums below
                (colNumberMax, synColSumDict) = write_syn_dataset(csvPathname, rowCount, colCount, SEEDPERFILE)

                parseKey = h2o_cmd.parseFile(None, csvPathname, key2=key2, timeoutSecs=timeoutSecs, doSummary=False)
                print "Parse result['destination_key']:", parseKey['destination_key']
                inspect = h2o_cmd.runInspect(None, parseKey['destination_key'], timeoutSecs=timeoutSecs)
                num_cols = inspect['num_cols']
                num_rows = inspect['num_rows']

                summaryResult = h2o_cmd.runSummary(key=key2, timeoutSecs=timeoutSecs)
                h2o_cmd.infoFromSummary(summaryResult, noPrint=True)

                self.assertEqual(colNumberMax+1, num_cols, 
                    msg="generated %s cols (including output).  parsed to %s cols" % (colNumberMax+1, num_cols))
                self.assertEqual(rowCount, num_rows, 
                    msg="generated %s rows, parsed to %s rows" % (rowCount, num_rows))

                summary = summaryResult['summary']
                columnsList = summary['columns']
                self.assertEqual(colNumberMax+1, len(columnsList), 
                    msg="generated %s cols (including output).  summary has %s columns" % (colNumberMax+1, len(columnsList)))

                for columns in columnsList:
                    N = columns['N']
                    # self.assertEqual(N, rowCount)
                    name = columns['name']
                    stype = columns['type']

                    histogram = columns['histogram']
                    bin_size = histogram['bin_size']
                    bin_names = histogram['bin_names']
                    bins = histogram['bins']
                    nbins = histogram['bins']

                    # definitely not enums
                    zeros = columns['zeros']
                    na = columns['na']
                    smax = columns['max']
                    smin = columns['min']
                    mean = columns['mean']
                    sigma = columns['sigma']

                    # a single 1 in the last col
                    if name == "V" + str(colNumberMax): # h2o puts a "V" prefix
                        synZeros = num_rows - 1
                        synSigma = None # not sure..depends on the # rows somehow (0 count vs 1 count)
                        synMean = 1.0/num_rows # why does this need to be a 1 entry list
                        synMin = [0.0, 1.0]
                        synMax = [1.0, 0.0]
                    elif name == ("V1"):
                        # can reverse-engineer the # of zeroes, since data is always 1
                        synSum = synColSumDict[1] # could get the same sum for all ccols
                        synZeros = num_rows - synSum
                        synSigma = 0.50
                        synMean = (synSum + 0.0)/num_rows
                        synMin = [0.0, 1.0]
                        synMax = [1.0, 0.0]
                    else:
                        synZeros = num_rows
                        synSigma = 0.0
                        synMean = 0.0
                        synMin = [0.0]
                        synMax = [0.0]

                    # print zeros, synZeros
                    self.assertAlmostEqual(float(mean), synMean, places=6,
                        msg='col %s mean %s is not equal to generated mean %s' % (name, mean, 0))

                    # why are min/max one-entry lists in summary result. Oh..it puts N min, N max
                    self.assertEqual(smin, synMin,
                        msg='col %s min %s is not equal to generated min %s' % (name, smin, synMin))

                    # reverse engineered the number of zeroes, knowing data was always 1 if present?
                    if name == "V65536" or name == "V65537":
                        print "columns around possible zeros mismatch:", h2o.dump_json(columns)

                    self.assertEqual(zeros, synZeros,
                        msg='col %s zeros %s is not equal to generated zeros count %s' % (name, zeros, synZeros))

                    # our random generation will have some variance for col 1. so just check to 2 places
                    if synSigma:
                        self.assertAlmostEqual(float(sigma), synSigma, places=2,
                            msg='col %s sigma %s is not equal to generated sigma %s' % (name, sigma, synSigma))

                    if CHECK_MAX:
                        self.assertEqual(smax, synMax,
                            msg='col %s max %s is not equal to generated max %s' % (name, smax, synMax))

                    self.assertEqual(0, na,
                        msg='col %s num_missing_values %d should be 0' % (name, na))


if __name__ == '__main__':
    h2o.unit_main()
