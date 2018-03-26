# Overview

Cassandra database (including DataStax Enterprise - DSE) uses an immutable file structure, called SSTable, to store data physically on disk. With such an append-only structure, "tombstone" is needed to mark the deletion of a piece of data in Cassandra (aka, soft deletion). The downside of a "tombstone", though, is that it will slow down the read performance because Cassandra still needs to scan all tombstoned data in order to return the correct result and this process conusmes computer resources. 

Cassandra evicts out tombstones through the "compaction" process. But in order to avoid some edge cases about data correctness (e.g. to avoid zombie data resurrection), tombstones need to stay in Cassandra for at least gc_grace_seconds (default 10 days). So if the age of a tombstone is less than gc_grace_seconds, the compaction process will not touch it. This means that if not properly planned (from data modeling and application development perspective), there could have execessive amount of tombstones in Cassandra system and therefore the overall read performance could be suffered.

From my field experience with Cassandra and DSE consulting, it is not an uncommon situation to see too many tombstones in a Cassandra/DSE system. When this happens, a frequent question from clients is "how can I find out how many tombstones are there in the system?". The "sstablemetadata" Cassandra tool can partially (and indirectly) answer that question by giving out an estimated droppable tombstone time period/count table (see example below) for a single SSTable. 

```
Estimated tombstone drop times:
1510712520:         1
1510712580:         1
1510712640:         1
```

In order to get the total amount of tombstones in the system for a Cassandra table, you have to sum the droppable tombstone counts for all time periods and then repeat the process for all SSTables for that Cassandra table. This process is a little bit cubersome and more importantly, it can't tell you the number of tombstones of different kinds. In lieu of this, I write a tool (as presented in this repository) to help find tombstone counts for a Cassandra table, both in total and at different category levels.

# Compilation and Usage of the Tool

***NOTE***: This tool **ONLY** supports Cassandra 3.x version and beyond. It doesn't support older versions of Cassanra!

After cloning the code locally, run the following command which, when successful, will generate a target jar file under "target" sub-directory. (target/tombstone-counter-1.0-SNAPSHOT.jar).
```
mvn clean compile package
```

To execute the program, run the following command:
```
java -cp target/tombstone-counter-1.0-SNAPSHOT.jar com.castools.TombStoneCounter <options>
```
The supported program options are as below.
```
usage: TombStoneCounter [-d <arg>] [-h] [-o <arg>] [-sp]

SSTable TombStone Counter for Apache Cassandra 3.x
Options:
  -d,--dir <arg>    Specify Cassandra table data directory
  -h,--help         Displays this help message.
  -o,--output <arg> Specify output file for tombstone stats.
  -sp,--suppress    Suppress commandline display output. 
```
When "-d" or "-o" option is not specified, it will use the current directory as the default directory for Cassandra table data (SSTable files) directory and the output diretory for generated tombstone statistics file.

If "-sp" option is specified, it will not display tombstone detail information on the command-line output while processing each SSTable.

## Output

When running the tool with specified Cassanra table data directory ("-d") option, the tool will generate a tombstone statistics (csv) file that includes the tombstone count of various categories for each SSTable file. Meanwhile, it will also prints out more read-able information on the console output. Below is an example:

```
$ java -cp target/tombstone-counter-1.0-SNAPSHOT.jar com.castools.TombStoneCounter -d /var/lib/cassandra/data/testks/testbl-0065f581c95311e7bea2f709ea23126f

Processing SSTable data files under directory: /var/lib/cassandra/data/testks/testbl-0065f581c95311e7bea2f709ea23126f

   Analyzing SSTable File:mc-2-big-Data.db
      Total partition/row count: 4/6
      Tomstone Count (Total): 6
      Tomstone Count (Partition): 0
      Tomstone Count (Range): 0
      Tomstone Count (ComplexColumn): 0
      Tomstone Count (Row) - Deletion: 0
      Tomstone Count (Row) - TTL: 1
      Tomstone Count (Cell) - Deletion: 5
      Tomstone Count (Cell) - TTL: 0

   Analyzing SSTable File:mc-1-big-Data.db
      Total partition/row count: 3/7
      Tomstone Count (Total): 9
      Tomstone Count (Partition): 1
      Tomstone Count (Range): 2
      Tomstone Count (ComplexColumn): 3
      Tomstone Count (Row) - Deletion: 1
      Tomstone Count (Row) - TTL: 0
      Tomstone Count (Cell) - Deletion: 2
      Tomstone Count (Cell) - TTL: 0

   Analyzing SSTable File:mc-3-big-Data.db
      Total partition/row count: 1/1
      Tomstone Count (Total): 3
      Tomstone Count (Partition): 0
      Tomstone Count (Range): 0
      Tomstone Count (ComplexColumn): 1
      Tomstone Count (Row) - Deletion: 0
      Tomstone Count (Row) - TTL: 0
      Tomstone Count (Cell) - Deletion: 0
      Tomstone Count (Cell) - TTL: 2
```

Meanwhile, a tomstone statistics file (**tombstone_stats.csv**) is generated in the current directory with the following contents. This statsitics file can be easily further analyzed using other tools like Excel
```
$ cat tombstone_stats.csv
sstable_data_file,part_cnt,row_cnt,total_ts_cnt,ts_part_cnt,ts_range_cnt,ts_complexcol_cnt,ts_row_del_cnt,ts_row_ttl_cnt,ts_cell_del_cnt,ts_cell_ttl_cnt
mc-2-big-Data.db,4,6,6,0,0,0,0,1,5,0
mc-1-big-Data.db,3,7,9,1,2,3,1,0,2,0
mc-3-big-Data.db,1,1,3,0,0,1,0,0,0,2
```
