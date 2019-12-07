package com.castools;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.rows.*;
import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.ISSTableScanner;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.schema.ColumnMetadata;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.schema.TableMetadataRef;
import org.apache.cassandra.tools.Util;
import org.apache.commons.cli.*;

import java.io.*;

import java.util.*;

/**
 * Hello world!
 *
 */
public class TombStoneCounter
{
    private static Options options = new Options();
    private static String OUTPUT_STATS_FILENAME = "tombstone_stats";
    private static int PROGRESS_BAR_BY_PART_CNT = 2000;

    static {
        DatabaseDescriptor.clientInitialization(false);

        // Partitioner is not set in client mode.
        if (DatabaseDescriptor.getPartitioner() == null)
            DatabaseDescriptor.setPartitionerUnsafe(Murmur3Partitioner.instance);

        Option helpOption = new Option("h", "help", false, "Displays this help message.");
        Option casTblFolderOption = new Option("d", "dir", true, "Specify Cassandra table data directory");
        Option outputOption = new Option("o", "output", true, "Specify output file for tombstone stats.");
        Option noCmdDisplayOption = new Option("sp", "suppress", false, "Suppress commandline display output.");

        options.addOption(helpOption);
        options.addOption(outputOption);
        options.addOption(casTblFolderOption);
        options.addOption(noCmdDisplayOption);
    }

    private static void usageAndExit() {
        try (PrintWriter errWriter = new PrintWriter(System.out, true)) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp(errWriter, 120, "TombStoneCounter",
                String.format("%nSSTable TombStone Counter for Apache Cassandra 3.x%nOptions:"),
                options, 2, 1, "", true);
        } finally {
            System.exit(-10);
        }
    }

    public static void main( String[] args )
    {
        String ssTableFolderName = System.getProperty("user.dir");
        String outputStatsFileName = OUTPUT_STATS_FILENAME + ".csv";

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = null;

        try {
            cmd = parser.parse(options, args);
        }
        catch (ParseException e) {
            System.err.format("\nError: Failure parsing arguments: %s", e.getMessage());
            usageAndExit();
        }

        if ( cmd.hasOption("h") ) {
            usageAndExit();
        }

        // If specified, get the Cassandra data directory name (for a particular table)
        if ( cmd.hasOption("d") ) {
            ssTableFolderName = cmd.getOptionValue("d");
        }

        File ssTableFolder = new File(ssTableFolderName);
        //System.out.println("Cassandra Table Folder: " + ssTableFolder.getAbsolutePath());
        if (!ssTableFolder.exists() || ! ssTableFolder.isDirectory() ) {
            System.out.println("\nError: Specified Cassandra data folder name neither exists, nor is a valid directory.");
            System.exit(-20);
        }

        // If specified, get the tombstone stats output file name
        if ( cmd.hasOption("o") ) {
            outputStatsFileName = cmd.getOptionValue("o");
        }

        File outputStatsFile = new File(outputStatsFileName);
        //System.out.println("Tombstone stats output file: " + outputStatsFile.getAbsolutePath());
        try {
            boolean statsFileCreated = outputStatsFile.createNewFile();

            if ( !statsFileCreated ) {
                System.out.println("\nError: The specified tombstone stats output file already exists.");
                System.exit(-30);
            }
        }
        catch (Exception ex) {
            System.out.println("\nError: Failed to create tombstone stats output file.");
            ex.printStackTrace();
            System.exit(-40);
        }

        boolean cmdDisp = true;
        if ( cmd.hasOption("sp") ) {
            cmdDisp = false;
        }

        // Get all SSTable data files (*Data.db) under the directory
        File[] ssTableDataFiles = ssTableFolder.listFiles(
            new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    if (name.endsWith("Data.db")) {
                        return true;
                    }
                    return false;
                }
            }
        );


        if ( (ssTableDataFiles == null) || (ssTableDataFiles.length < 1) ) {
            System.out.println("\nError: Either can't read the specified directory or there are no SSTable files under it.");
            System.exit(-60);
        }


        FileWriter fw = null;
        PrintWriter pw = null;
        try {
            fw = new FileWriter(outputStatsFile.getAbsolutePath(),  true);
            pw = new PrintWriter(new BufferedWriter(fw));

            // pw.println("sstable_data_file,part_cnt,row_cnt,total_ts_cnt,ts_part_cnt,ts_range_cnt,ts_complexcol_cnt,ts_row_del_cnt,ts_row_ttl_cnt,ts_cell_del_cnt,ts_cell_ttl_cnt");
            pw.println("sstable_data_file,part_cnt,total_ts_cnt,ts_part_cnt,ts_range_cnt,ts_complexcol_cnt,ts_row_del_cnt,ts_row_ttl_cnt,ts_cell_del_cnt,ts_cell_ttl_cnt");
        }
        catch (IOException ioe) {
            System.out.println("\nError: Failed to write to tombstone stats output file.");
            ioe.printStackTrace();
            System.exit(-70);
        }


        // Go through each SSTable data file for tombstone

        System.out.println("\nProcessing SSTable data files under directory: "  + ssTableFolderName);
        System.out.println("\n  ------------------------------------------------------------------------");
        System.out.println("  (scanning files, it may take long time to finish for large size of data)");
        System.out.println("  ------------------------------------------------------------------------");

        for ( File file: ssTableDataFiles ) {
            String fileName = file.getAbsolutePath();

            //if (cmdDisp) {
            System.out.println("\n  Analyzing SSTable File: " + file.getName());
            //}

            // total tombstone count
            long totalTombstoneCnt = 0;
            // partition level tombstone count
            int partTombstoneCnt = 0;
            // range tombstone count
            int rangeTombstoneCnt = 0;
            // row level tombstone count
            int rowTombstoneCnt = 0;
            int rowTTLedCnt = 0;
            // complex column tombstone count
            int complexColTombstoneCnt = 0;
            // cell level tombstone count
            int cellTombstoneCnt = 0;
            int cellTTLedCnt = 0;

            // total partition count
            long totalPartitionCnt = 0;
            // total row count
            //long totalRowCnt = 0;
            // total cell count
            //long totalCellCnt = 0;

            Descriptor descriptor = Descriptor.fromFilename(fileName);

            try {
                TableMetadata metaData = Util.metadataFromSSTable(descriptor);

                SSTableReader reader = SSTableReader.openNoValidation(descriptor, TableMetadataRef.forOfflineTools(metaData));
                ISSTableScanner scanner = reader.getScanner();

                while (scanner.hasNext()) {
                    UnfilteredRowIterator partition = scanner.next();
                    totalPartitionCnt++;

                    /**
                     *  Don't print progress bar at all
                     *     (print a "." for every PROGRESS_BAR_BY_PART_CNT partitions scanned
                     *
                    if (cmdDisp) {
                        if (totalPartitionCnt % PROGRESS_BAR_BY_PART_CNT == 0) {
                            if (totalPartitionCnt == PROGRESS_BAR_BY_PART_CNT) {
                                System.out.print("   ");
                            }
                            System.out.print(".");
                            System.out.flush();
                        }
                    }
                    */

                    if (!partition.partitionLevelDeletion().isLive()) {
                        totalTombstoneCnt++;
                        partTombstoneCnt++;
                    }

                    while (partition.hasNext()) {
                        Unfiltered unfiltered = partition.next();
                        //totalRowCnt++;

                        switch (unfiltered.kind()) {
                            case ROW:
                                Row row = (Row) unfiltered;

                                if (!row.deletion().isLive()) {
                                    totalTombstoneCnt++;
                                    rowTombstoneCnt++;
                                }

                                if (row.primaryKeyLivenessInfo().isExpiring()) {
                                    totalTombstoneCnt++;
                                    rowTTLedCnt++;
                                }

                                //System.out.println(row.primaryKeyLivenessInfo().toString());

                                for (Cell cell : row.cells()) {
                                    //totalCellCnt++;

                                    if (cell.isTombstone()) {
                                        totalTombstoneCnt++;
                                        cellTombstoneCnt++;
                                    }

                                    if ( !row.primaryKeyLivenessInfo().isExpiring() && cell.isExpiring() ) {
                                        totalTombstoneCnt++;
                                        cellTTLedCnt++;
                                    }
                                }

                                // If the row has any complex column deletion
                                if (row.hasComplexDeletion()) {
                                    Iterator<ColumnMetadata> cols = row.columns().iterator();

                                    while (cols.hasNext()) {
                                        ColumnMetadata colDef = cols.next();

                                        if (colDef.isComplex()) {
                                            ComplexColumnData ccol = row.getComplexColumnData(colDef);

                                            if (ccol != null) {
                                                if (!ccol.complexDeletion().isLive()) {
                                                    totalTombstoneCnt++;
                                                    complexColTombstoneCnt++;
                                                }
                                            }
                                        }
                                    }
                                }
                                break;

                            case RANGE_TOMBSTONE_MARKER:
                                totalTombstoneCnt++;
                                rangeTombstoneCnt++;
                                break;
                        }
                    }
                }

                // Display command-line output when requested
                if (cmdDisp) {
                    /**
                     *  Don't print progress bar at all
                     *     (print a "." for every PROGRESS_BAR_BY_PART_CNT partitions scanned
                     *
                    if (totalPartitionCnt > PROGRESS_BAR_BY_PART_CNT) {
                        System.out.println("\n");
                    }
                    */

                    //System.out.println("      Total partition/row count: " + totalPartitionCnt + "/" + totalRowCnt);
                    System.out.println("      Total partition: " + totalPartitionCnt );
                    System.out.println("      Tomstone Count (Total): " + totalTombstoneCnt);
                    System.out.println("      Tomstone Count (Partition): " + partTombstoneCnt);
                    System.out.println("      Tomstone Count (Range): " + rangeTombstoneCnt);
                    System.out.println("      Tomstone Count (ComplexColumn): " + complexColTombstoneCnt);
                    System.out.println("      Tomstone Count (Row) - Deletion: " + rowTombstoneCnt);
                    System.out.println("      Tomstone Count (Row) - TTL: " + rowTTLedCnt);
                    System.out.println("      Tomstone Count (Cell) - Deletion: " + cellTombstoneCnt);
                    System.out.println("      Tomstone Count (Cell) - TTL: " + cellTTLedCnt);
                }
            }
            catch (Exception ex) {
                System.out.println("Error processing SSTable file: " + fileName);
                ex.printStackTrace();
            }


            // NOTE : Do NOT display TotalRow count here because it is kind of meaningless.
            //        This is because when we deleted a partition, we really don't know how
            //        many rows were associated with that partition.
            //
            //        The "totalRowCnt" by the logic above is really the total number of rows
            //        for the active live partitions. It is not about the total number of rows
            //        within the SSTable, including both active live partitions and deleted
            //        partitions.

            //pw.printf("%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d\n",
            pw.printf("%s,%d,%d,%d,%d,%d,%d,%d,%d,%d\n",
                file.getName(),
                totalPartitionCnt,
                //totalRowCnt,
                totalTombstoneCnt,
                partTombstoneCnt,
                rangeTombstoneCnt,
                complexColTombstoneCnt,
                rowTombstoneCnt,
                rowTTLedCnt,
                cellTombstoneCnt,
                cellTTLedCnt);
        }

        try {
            if(pw != null)
                pw.close();

            if(fw != null)
                fw.close();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }

        System.exit(0);
    }
}