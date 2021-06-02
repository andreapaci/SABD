package it.sabd;

import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import scala.Tuple2;
import scala.Tuple3;
import scala.collection.JavaConverters;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.apache.spark.sql.functions.*;
import static org.apache.spark.sql.functions.lit;

public class Queries {


    public static long applyRegression(List<Tuple2<Date,Long>> list){

        ArrayList<Integer> x = new ArrayList();
        ArrayList<Long> y = new ArrayList();

        Calendar calendar = Calendar.getInstance();


        for (Tuple2<Date,Long> element : list){

            calendar.setTime(element._1);
            x.add(calendar.get(Calendar.DAY_OF_MONTH));
            y.add(element._2);

        }

        return Utils.regression(x,y,list.get(0)._1);


    }



    public static long computeQuery2(Dataset<Row> dfSVL, String destinationPath){

        long startTime = System.nanoTime();

        dfSVL = dfSVL.withColumn( "data_somministrazione",to_date(date_format(col("data_somministrazione"),
                "yyyy-LL-dd"))).filter(col("data_somministrazione").gt(lit("2021-01-31")));



        JavaPairRDD<Tuple3<Date,String, String>, Long> avoidCloneDaysRdd = dfSVL.toJavaRDD().mapToPair(x -> new Tuple2<Tuple3< Date,String,String>,Long>
                (new Tuple3<>(x.getDate(0),x.getString(2),x.getString(3)), (long) x.getInt(5))).reduceByKey((x, y) -> x+y);



        JavaPairRDD<Tuple2<String, String>, Iterable<Tuple2<Date,Long>>> rddpairSVL = avoidCloneDaysRdd.mapToPair(x ->
                new Tuple2<>(new Tuple2<>(x._1._2(),x._1._3()), new Tuple2<>(x._1._1(), x._2))).groupByKey();



        rddpairSVL.cache();


        JavaPairRDD<Tuple2<String, String>, List<Tuple2<Date, Long>>> rddByMonth = rddpairSVL.flatMapToPair(new Utils.daysGroupedByMonth());

        JavaPairRDD<Tuple2<String, String>, List<Tuple2<Date,Long>>> filteredRddByMonth = rddByMonth.filter(x -> x._2.size() > 1);

        JavaPairRDD<String, Iterable<Tuple2<String, Long>>> predictedRdd = filteredRddByMonth.mapToPair(x ->
                new Tuple2<>(Utils.firstDayNextMonth(x._2.get(0)._1) + " " + x._1._2,new Tuple2<>(x._1._1,applyRegression(x._2))))
                .groupByKey()
                .sortByKey();


        JavaPairRDD<String , List<Tuple2<String,Long>>> orderedRdd = predictedRdd.mapToPair(x ->
                new Tuple2<String, List<Tuple2<String,Long>>>(x._1, Utils.iterableToListTop5(x._2)));



        //.sortByKey(new TupleComparator(Comparator.<Date>naturalOrder(),Comparator.<Long>naturalOrder()));;

        orderedRdd.foreach(x->{
            System.out.println("Printing: " + x);
        });



        System.out.println("PORCODDIO");




        long endTime = System.nanoTime();

        //TODO * si puo salvare il file con un nome decente anzichè part-0000 ?

        //filteredRddByMonth.saveAsTextFile(destinationPath + "Query2");

        return (endTime-startTime);


    }







    public static long computeQuery1(Dataset<Row> dfSVSL, Dataset<Row> dfPST, String destinationPath){


        long startTime = System.nanoTime();

        //TODO *** siccome si chiede di ordinare il file all'inizio del processamento, vogliamo anche partizionare l'rdd in base alle date?

        //Per la query 1 non serve sapere il giorno
        dfSVSL = dfSVSL.withColumn( "data_somministrazione",to_date(date_format(col("data_somministrazione"), "yyyy-LL")));
        dfSVSL = dfSVSL.sort(col("data_somministrazione")).filter(col("data_somministrazione").gt(lit("2020-12-31")).lt(lit("2021-06-01")));



        //Convert dataframe to rdd taking only desired columns
        JavaPairRDD<Tuple2<Date,String>,Integer> rddpairSVSL = dfSVSL.toJavaRDD().mapToPair(x -> new Tuple2<Tuple2<Date, String>, Integer>
                (new Tuple2<Date, String>(x.getDate(0), x.getString(1)), x.getInt(2)));

        //Persist the rdd because was a large transformation from all dataframe to 3 columns rdd
        rddpairSVSL.cache();

        //Before sending data across the partitions, reduceByKey() merges the data locally using the same associative
        //function for optimized data shuffling
        JavaPairRDD<Tuple2<Date,String>,Integer> regionalSomministrationsPerMonth = rddpairSVSL.reduceByKey((x, y) -> x+y);

        //Trasform regionalSomministrationsPerMonth isolating area attribute as key so it can be joined with dfPSTCount rdd
        JavaPairRDD<String,Tuple2<Date,Integer>> regionalSomministrationsPerMonthJoinable = regionalSomministrationsPerMonth.
                mapToPair(x -> new Tuple2<String,Tuple2<Date,Integer>>(x._1._2,new Tuple2<Date, Integer>(x._1._1, x._2)));





        //Operators relative to PuntiSomministrazioneTipologia file
        JavaPairRDD<String, Integer> dfPSTPairs = dfPST.toJavaRDD().mapToPair(row -> new Tuple2<String,Integer>(row.getString(0), 1));

        //Persist the rdd because was a large transformation from all dataframe to 3 columns rdd
        dfPSTPairs.cache();

        JavaPairRDD<String, Integer> dfPSTCount = dfPSTPairs.reduceByKey((x,y) -> x+y);




        //Rdds join on area attribute
        JavaPairRDD<String, Tuple2<Tuple2<Date, Integer>, Integer>> rddJoin = regionalSomministrationsPerMonthJoinable.join(dfPSTCount);


        //Rdd with date , region and mean values of daily vaccines per center
        JavaPairRDD<Date,Tuple2<String,Double>> finalPairRdd = rddJoin.mapToPair(x -> (new Tuple2<Date,Tuple2<String,Double>>
                (x._2._1._1,new Tuple2<String, Double>(x._1, Utils.computeDailyDoses(x._2._1._1,( (double) x._2._1._2/x._2._2))))));

        //TODO *** DO we persist in cache this rdd ?

        // Preferred apply a UDF to an rdd rather than use a join with another rdd having only region short and extended name
        // and rather than keeping the name column along all the other rdds and calculations
        JavaPairRDD<Date,Tuple2<String,Double>> extendedNamesRdd = finalPairRdd.mapToPair(x -> (new Tuple2<Date,Tuple2<String,Double>>
                (x._1,new Tuple2<String, Double>(Utils.regionNameConverter(x._2._1),x._2._2)))).sortByKey();


        JavaPairRDD<String,Tuple2<String,Double>> finalRdd = extendedNamesRdd.mapToPair(x -> (new Tuple2<String,Tuple2<String,Double>>
                (Utils.dateConverter(x._1),new Tuple2<String, Double>(x._2._1,x._2._2))));


        long endTime = System.nanoTime();


        finalRdd.foreach(x->{

            System.out.println("Printing: " + x);

        });

        //TODO * si puo salvare il file con un nome decente anzichè part-0000 ?
        //TODO ordinare per regione anche?

        finalRdd.saveAsTextFile(destinationPath + "Query1");

        return (endTime-startTime);
    }






    public static long computeQuery1SQL(Dataset<Row> dfSVSL, Dataset<Row> dfPST, String destinationPath, SparkSession sSession){

        long startTime = System.nanoTime();
        dfSVSL.createOrReplaceTempView("SVSL");
        dfPST.createOrReplaceTempView("PST");

        dfSVSL.show(50);

        dfPST.show();

        Dataset<Row> dfPSTAverage = sSession.sql("SELECT area, count(area) as centri_tot FROM PST GROUP BY area");

        dfPSTAverage.createOrReplaceTempView("PST_average");

        dfPSTAverage.show();



        Dataset<Row> dfSVSLCenterAverage = sSession.sql("SELECT DISTINCT data_somministrazione, SVSL.area, sum(totale/centri_tot) as tot FROM SVSL JOIN PST_average on SVSL.area = PST_average.area GROUP BY data_somministrazione, SVSL.area, centri_tot");

        dfSVSLCenterAverage.createOrReplaceTempView("SVSL_centri_average");

        dfSVSLCenterAverage = dfSVSLCenterAverage.sort(col("data_somministrazione"));

        dfSVSLCenterAverage.show(50);

        long endTime = System.nanoTime();

        //dfSVSLCenterAverage.save(destinationPath + "Query1SQL");

        return (endTime-startTime);
    }




}
