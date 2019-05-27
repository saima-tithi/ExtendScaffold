import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.time.LocalDateTime;

public class ExtendScaffold {
    private static String read1 = "";
    private static String read2 = "";   
    private static String outputDir = "";
    private static String scaffoldFile = "";
    private static double avgReadLen = 0;
    private static String spadesKmerlen = "default";
    private static int minOverlapCircular = 5000;
    private static double minIdentityCircular = 95;
    private static double salmonReadFraction = 0.35;
    
    private static void parseArguments(String[] args) {
        if (args.length == 0) {
            System.exit(1);
        } else {
            for (int i = 0; i < args.length; i++) {
                if (args[i].startsWith("-")) {
                    if ((i + 1) >= args.length) {
                        System.out.println("Missing argument after " + args[i] + ".");
                        System.exit(1);
                    } else {
                        if (args[i].equals("-o")) {
                            outputDir = args[i + 1];
                        } else if (args[i].equals("-1")) {
                            read1 = args[i + 1];
                        } else if (args[i].equals("-2")) {
                            read2 = args[i + 1];
                        } else if (args[i].equals("-scaffold")) {
                            scaffoldFile = args[i + 1];
                        } else if (args[i].equals("-spadeskmer")) {
                            spadesKmerlen = args[i + 1];
                        } else if (args[i].equals("-minOverlapCircular")) {
                            minOverlapCircular = Integer.parseInt(args[i + 1]);
                        } else if (args[i].equals("-minIdentityCircular")) {
                            minIdentityCircular = Double.parseDouble(args[i + 1]);
                        } else if (args[i].equals("-readFrac")) {
                            salmonReadFraction = Double.parseDouble(args[i + 1]);
                        } else {
                            System.out.println("Invalid argument.");
                            System.exit(1);
                        }
                    }
                }
            }
        } // finish parsing arguments
    }
    
    private static void getReadLen() {
        String cmd = "";
        try {          
            if (read1.endsWith(".gz")) {
                cmd = "gzip -dc " + read1 
                        + " | awk 'NR%4 == 2 {lenSum+=length($0); readCount++;} END {print lenSum/readCount}'";
            } else {
                cmd = "awk 'NR%4 == 2 {lenSum+=length($0); readCount++;} END {print lenSum/readCount}' "
                        + read1;
            }
            
            FileWriter shellFileWriter = new FileWriter(outputDir + "/run.sh");
            shellFileWriter.write("#!/bin/bash\n");
            shellFileWriter.write(cmd);
            shellFileWriter.close();

            ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/run.sh");
            builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
            Process process = builder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String str = "";
            while ((str = reader.readLine()) != null) {
                avgReadLen = Double.parseDouble(str);
            }
            process.waitFor();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        
        if (avgReadLen == 0) {
            System.out.println("Could not extract average read length from read file.");
            System.exit(1);
        }
        System.out.println("Estimated average read length: " + avgReadLen);
    }
    
    private static void createBed() {
        //move contig.fasta and contig.fasta.fai from spades-res folder to outputDir
        File scaffoldFile = new File(outputDir + "/scaffold-truncated/tmp/spades-res/scaffold.fasta");
        if (scaffoldFile.exists() && !scaffoldFile.isDirectory()) 
        {
            String cmd = "";
            try {
                cmd = "cd " + outputDir + "/scaffold-truncated\n"
                        + "rm scaffold.fasta*\n"
                        + "cd tmp/spades-res\n" 
                        + "mv scaffold.fasta " + outputDir + "/scaffold-truncated\n"
                        + "mv scaffold.fasta.fai " + outputDir + "/scaffold-truncated\n";

                FileWriter shellFileWriter = new FileWriter(outputDir + "/scaffold-truncated/run.sh");
                shellFileWriter.write("#!/bin/bash\n");
                shellFileWriter.write(cmd);
                shellFileWriter.close();

                ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/scaffold-truncated/run.sh");
                builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
                Process process = builder.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                while (reader.readLine() != null) {
                }
                process.waitFor();
            } catch (Exception ex) {
                ex.printStackTrace();
            }           
        }       
        
        BufferedReader br = null;
        BufferedWriter bwOutLog = null;
        try {
            br = new BufferedReader(new FileReader(outputDir + "/scaffold-truncated/scaffold.fasta.fai"));
            bwOutLog = new BufferedWriter(new FileWriter(outputDir + "/output-log.txt", true));
            String str = br.readLine();
            String[] results = str.split("\t");
            int scaffoldLength = Integer.parseInt(results[1].trim());
            String scaffoldId = results[0].trim();

            bwOutLog.write("Trying to grow scaffold " + scaffoldId + " with length "
                        + scaffoldLength + "\n");
            if (scaffoldLength > 300000) {
                bwOutLog.write("Length of " + scaffoldId + " is already greater than 300kbp, so stop extending this one.\n");
            }
            br.close();
            bwOutLog.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void runAlignment() {
        String cmd = "";
        try {
            if (read2.isEmpty()) {
                cmd = "cd " + outputDir + "/scaffold-truncated\n"
                    + "rm -r salmon-index\n"
                    + "rm -r salmon-res\n"
                    + "rm salmon-mapped.sam\n"
                    + "salmon index -t scaffold.fasta -i salmon-index\n"
                    + "/usr/bin/time -f \"\t%E Elasped Real Time\" salmon quant -i salmon-index -l A "
                    + "-r " + read1 + " -o salmon-res --writeMappings -p 16 --quasiCoverage "
                    + salmonReadFraction
                    + " | samtools view -bS - | samtools view -h -F 0x04 - > salmon-mapped.sam\n";
            }
            else {
                    cmd = "cd " + outputDir + "/scaffold-truncated\n"
                            + "rm -r salmon-index\n"
                            + "rm -r salmon-res\n"
                            + "rm salmon-mapped.sam\n"
                            + "salmon index -t scaffold.fasta -i salmon-index\n"
                            + "/usr/bin/time -f \"\t%E Elasped Real Time\" salmon quant -i salmon-index -l A "
                            + "-1 " + read1 + " -2 " + read2 + " -o salmon-res --writeMappings -p 16 --quasiCoverage "
                            + salmonReadFraction
                            + "| samtools view -bS - | samtools view -h -F 0x04 - > salmon-mapped.sam\n";
            }
            
            FileWriter shellFileWriter = new FileWriter(outputDir + "/scaffold-truncated/run.sh");
            shellFileWriter.write("#!/bin/bash\n");
            shellFileWriter.write(cmd);
            shellFileWriter.close();

            ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/scaffold-truncated/run.sh");
            builder.redirectError(Redirect.appendTo(new File(outputDir + "/log-alignment.txt")));
            Process process = builder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while (reader.readLine() != null) {
            }
            process.waitFor();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
    private static void getMappedReads() {     
        String cmd = "";
        try {
            if (read2.isEmpty()) {
                cmd = "cd " + outputDir + "/scaffold-truncated\n" 
                    + "rm -r tmp\n" 
                    + "mkdir tmp\n"
                    + "bash filterbyname.sh in=" + read1
                    + " out=tmp/mapped_reads_1.fastq names="
                    + "salmon-mapped.sam include=t\n";
            }
            else {
                cmd = "cd " + outputDir + "/scaffold-truncated\n" 
                    + "rm -r tmp\n" 
                    + "mkdir tmp\n"
                    + "bash filterbyname.sh in=" + read1 + " in2=" + read2
                    + " out=tmp/mapped_reads_1.fastq out2=tmp/mapped_reads_2.fastq names="
                    + "salmon-mapped.sam include=t\n";
            }           

            FileWriter shellFileWriter = new FileWriter(outputDir + "/scaffold-truncated/run.sh");
            shellFileWriter.write("#!/bin/bash\n");
            shellFileWriter.write(cmd);
            shellFileWriter.close();

            ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/scaffold-truncated/run.sh");
            builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
            Process process = builder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while (reader.readLine() != null) {
            }
            process.waitFor();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
    private static void runSpades() {
        String cmd = "";
        try {
            if (read2.isEmpty()) {
                if (spadesKmerlen.equals("default")) {
                    cmd = "cd " + outputDir + "/scaffold-truncated/tmp\n" 
                        + "/usr/bin/time -f \"\t%E Elasped Real Time\" spades.py -o "
                        + "spades-res -s mapped_reads_1.fastq "
                        + "--trusted-contigs ../scaffold.fasta"
                        + " --only-assembler\n"
                        + "cd spades-res\n"
                        + "samtools faidx scaffolds.fasta\n";
                }
                else {
                    cmd = "cd " + outputDir + "/scaffold-truncated/tmp\n" 
                        + "/usr/bin/time -f \"\t%E Elasped Real Time\" spades.py -o "
                        + "spades-res -s mapped_reads_1.fastq "
                        + "--trusted-contigs ../scaffold.fasta -k " + spadesKmerlen
                        + " --only-assembler\n"
                        + "cd spades-res\n"
                        + "samtools faidx scaffolds.fasta\n";
                }
            }
            else {
                if (spadesKmerlen.equals("default")) {
                    cmd = "cd " + outputDir + "/scaffold-truncated/tmp\n" 
                        + "/usr/bin/time -f \"\t%E Elasped Real Time\" spades.py -o "
                        + "spades-res -1 mapped_reads_1.fastq -2 mapped_reads_2.fastq "
                        + "--trusted-contigs ../scaffold.fasta"
                        + " --only-assembler\n"
                        + "cd spades-res\n"
                        + "samtools faidx scaffolds.fasta\n";
                }
                else {
                    cmd = "cd " + outputDir + "/scaffold-truncated/tmp\n" 
                            + "/usr/bin/time -f \"\t%E Elasped Real Time\" spades.py -o "
                            + "spades-res -1 mapped_reads_1.fastq -2 mapped_reads_2.fastq "
                            + "--trusted-contigs ../scaffold.fasta -k " + spadesKmerlen
                            + " --only-assembler\n"
                            + "cd spades-res\n"
                            + "samtools faidx scaffolds.fasta\n";
                }
            }

            FileWriter shellFileWriter = new FileWriter(outputDir + "/scaffold-truncated/run.sh");
            shellFileWriter.write("#!/bin/bash\n");
            shellFileWriter.write(cmd);
            shellFileWriter.close();

            ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/scaffold-truncated/run.sh");
            builder.redirectError(Redirect.appendTo(new File(outputDir + "/log-assembly.txt")));
            Process process = builder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while (reader.readLine() != null) {
            }
            process.waitFor();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
    private static int getScaffoldFromScaffolds() {    
        int maxScaffoldLength = 0;
        String maxScaffoldId = "";
        
        File scaffoldFile = new File(outputDir + "/scaffold-truncated/tmp/spades-res/scaffolds.fasta");
        if (scaffoldFile.exists() && !scaffoldFile.isDirectory()) 
        {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new FileReader(outputDir + "/scaffold-truncated/tmp/spades-res/scaffolds.fasta.fai"));
                String str = br.readLine();
                str = str.trim();
                String[] results = str.split("\t");
                maxScaffoldId = results[0].trim();
                maxScaffoldLength = Integer.parseInt(results[1].trim());            
                br.close();
                
                if (!maxScaffoldId.equals("") && maxScaffoldLength != 0)
                {
                    String cmd = "";
                    
                    cmd = "cd " + outputDir + "/scaffold-truncated/tmp/spades-res\n"
                            + "bash filterbyname.sh "
                            + "in=scaffolds.fasta "
                            + "out=scaffold.fasta names=" + maxScaffoldId
                            + " include=t\n"
                            + "samtools faidx scaffold.fasta\n";
                    
                    FileWriter shellFileWriter = new FileWriter(outputDir + "/scaffold-truncated/run.sh");
                    shellFileWriter.write("#!/bin/bash\n");
                    shellFileWriter.write(cmd);
                    shellFileWriter.close();
        
                    ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/scaffold-truncated/run.sh");
                    builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
                    Process process = builder.start();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    while (reader.readLine() != null) {
                    }
                    process.waitFor();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        //for the first time, no spades result
        else {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new FileReader(outputDir + "/scaffold-truncated/scaffold.fasta.fai"));
                String str = br.readLine();
                str = str.trim();
                String[] results = str.split("\t");
                maxScaffoldLength = Integer.parseInt(results[1].trim());            
                br.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        return maxScaffoldLength;
    }
    
    private static int getScaffoldLenFromTruncatedExtend() {
        int scaffoldLength = 0;
        File scaffoldFile = new File(outputDir + "/scaffold-truncated/scaffold.fasta");
        if (scaffoldFile.exists() && !scaffoldFile.isDirectory()) 
        {
            BufferedReader br = null;
            try {
                br = new BufferedReader(new FileReader(outputDir + "/scaffold-truncated/scaffold.fasta.fai"));
                String str = br.readLine();
                str = str.trim();
                String[] results = str.split("\t");
                scaffoldLength = Integer.parseInt(results[1].trim());            
                br.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        else {
            //for the first time, no truncated scaffold
            BufferedReader br = null;
            try {
                br = new BufferedReader(new FileReader(outputDir + "/scaffold.fasta.fai"));
                String str = br.readLine();
                str = str.trim();
                String[] results = str.split("\t");
                scaffoldLength = Integer.parseInt(results[1].trim());            
                br.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return scaffoldLength;
    }
    
    private static void updateCurrentScaffold() {
        File scaffoldFile = new File(outputDir + "/scaffold-truncated/scaffold.fasta");
        if (scaffoldFile.exists() && !scaffoldFile.isDirectory()) 
        {
            String cmd = "";
            try {
                cmd = "cd " + outputDir + "\n"
                        + "rm scaffold.fasta*\n"
                        + "cd scaffold-truncated\n" 
                        + "cp scaffold.fasta " + outputDir + "\n"
                        + "cp scaffold.fasta.fai " + outputDir + "\n";

                FileWriter shellFileWriter = new FileWriter(outputDir + "/run.sh");
                shellFileWriter.write("#!/bin/bash\n");
                shellFileWriter.write(cmd);
                shellFileWriter.close();

                ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/run.sh");
                builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
                Process process = builder.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                while (reader.readLine() != null) {
                }
                process.waitFor();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }       
    }
    
    private static void createBedForTruncatedScaffold(int truncatedLen) {
        BufferedReader br = null;
        BufferedWriter bw = null;
        try {
            br = new BufferedReader(new FileReader(outputDir + "/scaffold.fasta.fai"));
            bw = new BufferedWriter(new FileWriter(outputDir + "/scaffold-truncated.bed"));
            String str = br.readLine();
            String[] results = str.split("\t");
            int scaffoldLength = Integer.parseInt(results[1].trim());
            if (scaffoldLength >= truncatedLen) {
                String scaffoldId = results[0].trim();
                bw.write(scaffoldId + "\t" + truncatedLen + "\t" + (scaffoldLength - truncatedLen) + "\n");
            }
            br.close();
            bw.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void growScaffoldWithAssembly() {
        int iteration = 1;
        boolean extendContig = true;        
        int prevLength = 0;
        
        while (extendContig) 
        {
            int currentLength = getScaffoldFromScaffolds();
            if (currentLength > 300000) {
                extendContig = false;
            }
            if (currentLength > prevLength)
            {
                prevLength = currentLength;
                createBed();
                runAlignment();
                getMappedReads();
                runSpades();
                
                File scaffoldFile = new File(outputDir + "/scaffold-truncated/tmp/spades-res/scaffolds.fasta");
                if (scaffoldFile.exists() && !scaffoldFile.isDirectory()) {
                    iteration++;
                    extendContig = true;
                } else {
                    extendContig = false;
                }
            }
            else {
                extendContig = false;
            }
            
            if (extendContig && iteration > 1000) {
                extendContig = false;
            }
        }        
    }
    
    private static void getTruncatedScaffoldAndExtend(int currentLength) {
        String cmd = "";
        
        //truncate 300bp
        createBedForTruncatedScaffold(300);
        
        try {
            cmd = "cd " + outputDir + "\n"
                    + "rm -r scaffold-truncated\n"
                    + "mkdir scaffold-truncated\n" 
                    + "bedtools getfasta -fi scaffold.fasta -bed scaffold-truncated.bed -fo scaffold-truncated/scaffold.fasta\n"
                    + "cd scaffold-truncated\n"
                    + "samtools faidx scaffold.fasta\n";

            FileWriter shellFileWriter = new FileWriter(outputDir + "/run.sh");
            shellFileWriter.write("#!/bin/bash\n");
            shellFileWriter.write(cmd);
            shellFileWriter.close();

            ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/run.sh");
            builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
            Process process = builder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while (reader.readLine() != null) {
            }
            process.waitFor();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    
        growScaffoldWithAssembly();      
        
        int lengthFromGrowingTruncatedScaffold = 0;
        lengthFromGrowingTruncatedScaffold = getScaffoldLenFromTruncatedExtend();
        if (lengthFromGrowingTruncatedScaffold > currentLength) {
            return;
        }
        else 
        {
            //truncate 500bp
            createBedForTruncatedScaffold(500);
            cmd = "";
            try {
                cmd = "cd " + outputDir + "\n"
                        + "rm -r scaffold-truncated\n"
                        + "mkdir scaffold-truncated\n" 
                        + "bedtools getfasta -fi scaffold.fasta -bed scaffold-truncated.bed -fo scaffold-truncated/scaffold.fasta\n"
                        + "cd scaffold-truncated\n"
                        + "samtools faidx scaffold.fasta\n";

                FileWriter shellFileWriter = new FileWriter(outputDir + "/run.sh");
                shellFileWriter.write("#!/bin/bash\n");
                shellFileWriter.write(cmd);
                shellFileWriter.close();

                ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/run.sh");
                builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
                Process process = builder.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                while (reader.readLine() != null) {
                }
                process.waitFor();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            
            growScaffoldWithAssembly();
        }
        
        lengthFromGrowingTruncatedScaffold = getScaffoldLenFromTruncatedExtend();
        if (lengthFromGrowingTruncatedScaffold > currentLength) {
            return;
        }
        else 
        {
            //truncate 700bp
            createBedForTruncatedScaffold(700);
            cmd = "";
            try {
                cmd = "cd " + outputDir + "\n"
                        + "rm -r scaffold-truncated\n"
                        + "mkdir scaffold-truncated\n" 
                        + "bedtools getfasta -fi scaffold.fasta -bed scaffold-truncated.bed -fo scaffold-truncated/scaffold.fasta\n"
                        + "cd scaffold-truncated\n"
                        + "samtools faidx scaffold.fasta\n";

                FileWriter shellFileWriter = new FileWriter(outputDir + "/run.sh");
                shellFileWriter.write("#!/bin/bash\n");
                shellFileWriter.write(cmd);
                shellFileWriter.close();

                ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/run.sh");
                builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
                Process process = builder.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                while (reader.readLine() != null) {
                }
                process.waitFor();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            
            growScaffoldWithAssembly();
        }
        
        lengthFromGrowingTruncatedScaffold = getScaffoldLenFromTruncatedExtend();
        if (lengthFromGrowingTruncatedScaffold > currentLength) {
            return;
        }
        else 
        {
            //truncate 1000bp
            createBedForTruncatedScaffold(1000);
            cmd = "";
            try {
                cmd = "cd " + outputDir + "\n"
                        + "rm -r scaffold-truncated\n"
                        + "mkdir scaffold-truncated\n" 
                        + "bedtools getfasta -fi scaffold.fasta -bed scaffold-truncated.bed -fo scaffold-truncated/scaffold.fasta\n"
                        + "cd scaffold-truncated\n"
                        + "samtools faidx scaffold.fasta\n";

                FileWriter shellFileWriter = new FileWriter(outputDir + "/run.sh");
                shellFileWriter.write("#!/bin/bash\n");
                shellFileWriter.write(cmd);
                shellFileWriter.close();

                ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/run.sh");
                builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
                Process process = builder.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                while (reader.readLine() != null) {
                }
                process.waitFor();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            
            growScaffoldWithAssembly();
        }
        
        lengthFromGrowingTruncatedScaffold = getScaffoldLenFromTruncatedExtend();
        if (lengthFromGrowingTruncatedScaffold > currentLength) {
            return;
        }
        else 
        {
            //truncate 1300bp
            createBedForTruncatedScaffold(1300);
            cmd = "";
            try {
                cmd = "cd " + outputDir + "\n"
                        + "rm -r scaffold-truncated\n"
                        + "mkdir scaffold-truncated\n" 
                        + "bedtools getfasta -fi scaffold.fasta -bed scaffold-truncated.bed -fo scaffold-truncated/scaffold.fasta\n"
                        + "cd scaffold-truncated\n"
                        + "samtools faidx scaffold.fasta\n";

                FileWriter shellFileWriter = new FileWriter(outputDir + "/run.sh");
                shellFileWriter.write("#!/bin/bash\n");
                shellFileWriter.write(cmd);
                shellFileWriter.close();

                ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/run.sh");
                builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
                Process process = builder.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                while (reader.readLine() != null) {
                }
                process.waitFor();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            
            growScaffoldWithAssembly();
        }
        
        lengthFromGrowingTruncatedScaffold = getScaffoldLenFromTruncatedExtend();
        if (lengthFromGrowingTruncatedScaffold > currentLength) {
            return;
        }
        else 
        {
            //truncate 1500bp
            createBedForTruncatedScaffold(1500);
            cmd = "";
            try {
                cmd = "cd " + outputDir + "\n"
                        + "rm -r scaffold-truncated\n"
                        + "mkdir scaffold-truncated\n" 
                        + "bedtools getfasta -fi scaffold.fasta -bed scaffold-truncated.bed -fo scaffold-truncated/scaffold.fasta\n"
                        + "cd scaffold-truncated\n"
                        + "samtools faidx scaffold.fasta\n";

                FileWriter shellFileWriter = new FileWriter(outputDir + "/run.sh");
                shellFileWriter.write("#!/bin/bash\n");
                shellFileWriter.write(cmd);
                shellFileWriter.close();

                ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/run.sh");
                builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
                Process process = builder.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                while (reader.readLine() != null) {
                }
                process.waitFor();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            
            growScaffoldWithAssembly();
        }
        
        lengthFromGrowingTruncatedScaffold = getScaffoldLenFromTruncatedExtend();
        if (lengthFromGrowingTruncatedScaffold > currentLength) {
            return;
        }
        else 
        {
            //truncate 1700bp
            createBedForTruncatedScaffold(1700);
            cmd = "";
            try {
                cmd = "cd " + outputDir + "\n"
                        + "rm -r scaffold-truncated\n"
                        + "mkdir scaffold-truncated\n" 
                        + "bedtools getfasta -fi scaffold.fasta -bed scaffold-truncated.bed -fo scaffold-truncated/scaffold.fasta\n"
                        + "cd scaffold-truncated\n"
                        + "samtools faidx scaffold.fasta\n";

                FileWriter shellFileWriter = new FileWriter(outputDir + "/run.sh");
                shellFileWriter.write("#!/bin/bash\n");
                shellFileWriter.write(cmd);
                shellFileWriter.close();

                ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/run.sh");
                builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
                Process process = builder.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                while (reader.readLine() != null) {
                }
                process.waitFor();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            
            growScaffoldWithAssembly();
        }
        
        lengthFromGrowingTruncatedScaffold = getScaffoldLenFromTruncatedExtend();
        if (lengthFromGrowingTruncatedScaffold > currentLength) {
            return;
        }
        else 
        {
            //truncate 2000bp
            createBedForTruncatedScaffold(2000);
            cmd = "";
            try {
                cmd = "cd " + outputDir + "\n"
                        + "rm -r scaffold-truncated\n"
                        + "mkdir scaffold-truncated\n" 
                        + "bedtools getfasta -fi scaffold.fasta -bed scaffold-truncated.bed -fo scaffold-truncated/scaffold.fasta\n"
                        + "cd scaffold-truncated\n"
                        + "samtools faidx scaffold.fasta\n";

                FileWriter shellFileWriter = new FileWriter(outputDir + "/run.sh");
                shellFileWriter.write("#!/bin/bash\n");
                shellFileWriter.write(cmd);
                shellFileWriter.close();

                ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/run.sh");
                builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
                Process process = builder.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                while (reader.readLine() != null) {
                }
                process.waitFor();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            
            growScaffoldWithAssembly();
        }
    }
    
    private static boolean checkCircularity() 
    {
        String cmd = "";
        try {
            cmd = "cd " + outputDir + "\n" 
                    + "rm -r blastn-*\n";

            FileWriter shellFileWriter = new FileWriter(outputDir + "/run.sh");
            shellFileWriter.write("#!/bin/bash\n");
            shellFileWriter.write(cmd);
            shellFileWriter.close();

            ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/run.sh");
            builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
            Process process = builder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while (reader.readLine() != null) {
            }
            process.waitFor();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
       
        //create query and subject fasta
        BufferedReader br = null;
        BufferedWriter bw1 = null;
        BufferedWriter bw2 = null;
        try {
            br = new BufferedReader(new FileReader(outputDir + "/scaffold.fasta.fai"));
            bw1 = new BufferedWriter(new FileWriter(outputDir + "/blastn-subject-1stround.bed"));
            bw2 = new BufferedWriter(new FileWriter(outputDir + "/blastn-query-1stround.bed"));
            String str = br.readLine();
            String[] results = str.split("\t");
            int scaffoldLength = Integer.parseInt(results[1].trim());
            int intReadLen = (int)avgReadLen;
            if (scaffoldLength > (intReadLen*2)) {
                String scaffoldId = results[0].trim();
                bw1.write(scaffoldId + "\t" + 0 + "\t" + (scaffoldLength - (intReadLen*2)) + "\n");
                bw2.write(scaffoldId + "\t" + (scaffoldLength - (intReadLen*2)) + "\t" + (scaffoldLength-intReadLen) + "\n");
            }
            br.close();
            bw1.close();
            bw2.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        cmd = "";
        try {
            cmd = "cd " + outputDir + "\n" 
                    + "bedtools getfasta -fi scaffold.fasta -bed blastn-subject-1stround.bed -fo blastn-subject-1stround.fasta\n"
                    + "bedtools getfasta -fi scaffold.fasta -bed blastn-query-1stround.bed -fo blastn-query-1stround.fasta\n"
                    + "samtools faidx blastn-subject-1stround.fasta\n"
                    + "samtools faidx blastn-query-1stround.fasta\n"
                    + "makeblastdb -in blastn-subject-1stround.fasta -dbtype nucl\n"
                    + "blastn -query blastn-query-1stround.fasta -db blastn-subject-1stround.fasta -num_threads 16 -outfmt '7' -out blastn-res-1stround.txt\n";

            FileWriter shellFileWriter = new FileWriter(outputDir + "/run.sh");
            shellFileWriter.write("#!/bin/bash\n");
            shellFileWriter.write(cmd);
            shellFileWriter.close();

            ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/run.sh");
            builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
            Process process = builder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while (reader.readLine() != null) {
            }
            process.waitFor();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        
        //parse blastn result
        BufferedWriter bwCircularOutputLog = null;
        boolean isCircular = false;
        int subjectStart = 0;
        int queryStart = 0;
        try {
            br = new BufferedReader(new FileReader(outputDir + "/blastn-res-1stround.txt"));
            bwCircularOutputLog = new BufferedWriter(new FileWriter(outputDir + "/circularity-output-log.txt", true));
            String str = "";
            String[] results;
            while ((str = br.readLine()) != null && !isCircular) 
            {
                if (!str.startsWith("#")) 
                {
                    str = str.trim();
                    results = str.split("\t");
                    double percentIden = Double.parseDouble(results[2].trim());
                    int alignmentLen = Integer.parseInt(results[3].trim());
                    subjectStart = Integer.parseInt(results[8].trim());
                    queryStart = Integer.parseInt(results[6].trim());
                    int minAlignmentLength = (int)(avgReadLen*0.95);
                    /*if (minOverlapCircular <= 100) {
                        minAlignmentLength = 100;
                    }
                    else {
                        minAlignmentLength = minOverlapCircular-100;
                    }*/
                    if (percentIden >= 95
                        && alignmentLen >= minAlignmentLength) {
                        bwCircularOutputLog.write("Scaffold seems circular. Scaffold position " + results[6]
                            + " to " + results[7] + " mapped to position " + results[8] + " to " + results[9] 
                            + " with " + percentIden + "% identity and " + alignmentLen + " alignment length.\n");
                        isCircular = true;
                    }
                    else {
                        bwCircularOutputLog.write("Scaffold does not seem to be circular. Scaffold position " + results[6]
                            + " to " + results[7] + " mapped to position " + results[8] + " to " + results[9]
                            + " with " + percentIden + "% identity and " + alignmentLen + " alignment length.\n");
                    }
                }                
            }
            br.close();
            bwCircularOutputLog.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        //blastn subject query 2nd round
        if (isCircular) 
        {
            try {
                br = new BufferedReader(new FileReader(outputDir + "/scaffold.fasta.fai"));
                bw1 = new BufferedWriter(new FileWriter(outputDir + "/blastn-subject-2ndround.bed"));
                bw2 = new BufferedWriter(new FileWriter(outputDir + "/blastn-query-2ndround.bed"));
                String str = br.readLine();
                String[] results = str.split("\t");
                int scaffoldLength = Integer.parseInt(results[1].trim());
                int intReadLen = (int)avgReadLen;
                if (scaffoldLength > (intReadLen*2)) {
                    String scaffoldId = results[0].trim();
                    bw1.write(scaffoldId + "\t" + 0 + "\t" + subjectStart + "\n");
                    bw2.write(scaffoldId + "\t" + (scaffoldLength - (intReadLen*2) - subjectStart) + "\t" + (scaffoldLength-(intReadLen*2)) + "\n");
                }
                br.close();
                bw1.close();
                bw2.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            
            cmd = "";
            try {
                cmd = "cd " + outputDir + "\n" 
                        + "bedtools getfasta -fi scaffold.fasta -bed blastn-subject-2ndround.bed -fo blastn-subject-2ndround.fasta\n"
                        + "bedtools getfasta -fi scaffold.fasta -bed blastn-query-2ndround.bed -fo blastn-query-2ndround.fasta\n"
                        + "samtools faidx blastn-subject-2ndround.fasta\n"
                        + "samtools faidx blastn-query-2ndround.fasta\n"
                        + "makeblastdb -in blastn-subject-2ndround.fasta -dbtype nucl\n"
                        + "blastn -query blastn-query-2ndround.fasta -db blastn-subject-2ndround.fasta -num_threads 16 -outfmt '7' -out blastn-res-2ndround.txt\n";

                FileWriter shellFileWriter = new FileWriter(outputDir + "/run.sh");
                shellFileWriter.write("#!/bin/bash\n");
                shellFileWriter.write(cmd);
                shellFileWriter.close();

                ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/run.sh");
                builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
                Process process = builder.start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                while (reader.readLine() != null) {
                }
                process.waitFor();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        return isCircular;
    }
    
    private static void extendOneScaffold() {
        int iteration = 1;
        String cmd = "";
        try {
            /*cmd = "cd " + outputDir + "\n"
                + "cp " + scaffoldFile + " scaffold.fasta\n"
                + "samtools faidx scaffold.fasta\n";*/
            cmd = "cd " + outputDir + "\n"
                + "mkdir scaffold-truncated\n"
                + "cd scaffold-truncated\n"
                + "cp " + scaffoldFile + " scaffold.fasta\n"                
                + "samtools faidx scaffold.fasta\n";
        
            FileWriter shellFileWriter = new FileWriter(outputDir + "/run.sh");
            shellFileWriter.write("#!/bin/bash\n");
            shellFileWriter.write(cmd);
            shellFileWriter.close();
    
            ProcessBuilder builder = new ProcessBuilder("sh", outputDir + "/run.sh");
            builder.redirectError(Redirect.appendTo(new File(outputDir + "/log.txt")));
            Process process = builder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            while (reader.readLine() != null) {
            }
            process.waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        boolean extendContig = true;
        
        //grow for the first time
        int prevLength = 0;
        updateCurrentScaffold();
        if (checkCircularity())
        {
            extendContig = false;
        }
        else
        {
            growScaffoldWithAssembly();
        }
        
        while (extendContig) {           
            int currentLength = getScaffoldLenFromTruncatedExtend();
            if (currentLength > prevLength)
            {
                prevLength = currentLength;
                updateCurrentScaffold();
                if (checkCircularity())
                {
                    extendContig = false;
                }
                else
                {
                    getTruncatedScaffoldAndExtend(currentLength);
                    iteration++;
                }               
            }
            else {
                extendContig = false;
            }
            
            if (extendContig && iteration > 100) {
                extendContig = false;
            }
        }
    }
    
    public static void main(String[] args) {
        parseArguments(args);
        System.out.println("Finished parsing input arguments");
        getReadLen();
        System.out.println("Started growing scaffold: " + LocalDateTime.now());      
        extendOneScaffold();
        System.out.println("Finished growing scaffold: " + LocalDateTime.now());
    }
}
