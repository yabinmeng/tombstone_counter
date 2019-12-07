
---
***NOTE***: 

1). This is a new branch (dse6_gradle) that is designed to work with DSE version 6.0+. 
    
2). The original branch (ossc3_dse5) was developed to only work with OSS C* 3.x versions, including compatible DSE 5.0.x and 5.1.x versions.

3). Neither branch is designed working with older version of C* (version 2.x and before)

**NOTE**: The provided release (v1.0) has been tested working with all the following DSE and C* versions:
* DSE 6.7.x (6.7.6)
* DSE 6.0.x (6.0.10)
* DSE 5.1.x (5.1.17)
* DSE 5.0.x (5.0.14)
* DDAC (5.1.16)
* OSS C* 3.11.x (3.11.5)

---

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

# Compiling the Tool

This utility relies on C* library to work properly. The original branch (ossc3_dse5) uses OSS C* library from Maven central library, as per the following dependency specified in "pom.xml" file.
```
<dependency>
   <groupId>org.apache.cassandra</groupId>
   <artifactId>cassandra-all</artifactId>
   <version>${cassandra.version}</version>
</dependency>
```

To use this tool against DSE 6.0+, the OSS C* library won't work and the DSE C* library is needed, which is NOT available on Maven central library. We need to add DSE C* library in a local repository, which is achieved by the following dependency setting in "build.gradle" file.

```
dependencies {
    // Maven central libraries
    ... ... 

    // Local DSE libraries
    compile fileTree(dir: 'libs', include: '*.jar')
}
```

Please **NOTE**:
* 'libs' is a sub-directory under the project home directory (where build.gradle file is). Once the project is cloned, create this folder manually and copy all DSE C* library jar files in it. 
* The location of the DSE C* library jar files are in the following location:
  * For packaged installation: /usr/share/dse/cassandra/lib
  * For tarball installation: <tarball_install_home>/resources/cassandra/lib 
  
***SIDE NOTE***: different DSE versions (e.g. 6.0.x vs. 6.7.x) may have differences regarding the list of availble libraries. The test that I've been doing is based on 6.7.x libraries. But this utility itself (and the provided release v1.0 jar file) has been tested against both DSE 6.0.x and DSE 6.7.x and it works fine. If you really want to be sure, you can simply rebuild this utility based on the matching DSE version. 
  
Once the DSE library files are copied, run the following commands to generate the final jar file (tombstone-counter-1.0.jar).
```
gradle clean build
```


# Using the Tool

To execute the program, run the following command:
```
java -cp target/tombstone-counter-1.0.jar com.castools.TombStoneCounter <options>
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

When running the tool with specified Cassanra table data directory ("-d") option, the tool will generate a tombstone statistics (csv) file that includes the tombstone count of various categories for each SSTable file. Meanwhile, it will also prints out more read-able information on the console output. Below is an example (against DSE 6.0.10):

```
$ java -cp ./tombstone-counter-1.0.jar com.castools.TombStoneCounter -d test.bkup.6010/testbl-fa44b341176a11eaa91a83381c121464/

Processing SSTable data files under directory: test.bkup.6010/testbl-fa44b341176a11eaa91a83381c121464/

  ------------------------------------------------------------------------
  (scanning files, it may take long time to finish for large size of data)
  ------------------------------------------------------------------------

  Analyzing SSTable File: aa-1-bti-Data.db
      Total partition: 8
      Tomstone Count (Total): 4
      Tomstone Count (Partition): 3
      Tomstone Count (Range): 0
      Tomstone Count (ComplexColumn): 0
      Tomstone Count (Row) - Deletion: 0
      Tomstone Count (Row) - TTL: 0
      Tomstone Count (Cell) - Deletion: 1
      Tomstone Count (Cell) - TTL: 0
```

Meanwhile, a tomstone statistics file (**tombstone_stats.csv**) is generated in the current directory with the following contents. This statsitics file can be easily further analyzed using other tools like Excel
```
$ cat tombstone_stats.csv
sstable_data_file,part_cnt,total_ts_cnt,ts_part_cnt,ts_range_cnt,ts_complexcol_cnt,ts_row_del_cnt,ts_row_ttl_cnt,ts_cell_del_cnt,ts_cell_ttl_cnt
aa-1-bti-Data.db,8,4,3,0,0,0,0,1,0
```


# Future Improvements

Currently this utility is single-threaded and scans SSTables on one DSE node (for a particular C* table) sequentially. For C* tables with lots of data, it may take a long time to complete the scan. This can be improved with the following 2 possible options:
1. Allow to scan multiple SSTables concurrently.
2. For one particular SSTable, allow to use multiple theads to scan it (similar to "-j <thread_num>" option in "nodetool upgradesstables" command.

Another improvement that can make the utility a little easier to use is:

3. Instead of providing the actual file system folder name (that contains the SSTable files to be scanned), the utility can simply take C* keyspace and table name as the input parammeters.
