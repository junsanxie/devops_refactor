package top.lazyr.script;

import cn.hutool.core.util.StrUtil;
import top.lazyr.architecture.diagram.Graph;
import top.lazyr.architecture.diagram.Node;
import top.lazyr.architecture.manager.GraphManager;
import top.lazyr.constant.ConsolePrinter;
import top.lazyr.io.output.TxtOutput;
import top.lazyr.parser.SourceCodeParser;
import top.lazyr.refactor.algorithm.genetic.nsgaii.Configuration;
import top.lazyr.refactor.algorithm.genetic.nsgaii.NSGA2;
import top.lazyr.refactor.algorithm.genetic.nsgaii.crossover.AbstractCrossover;
import top.lazyr.refactor.algorithm.genetic.nsgaii.crossover.SingleCrossover;
import top.lazyr.refactor.algorithm.genetic.nsgaii.mutation.AbstractMutation;
import top.lazyr.refactor.algorithm.genetic.nsgaii.mutation.SingleMutation;
import top.lazyr.refactor.algorithm.genetic.nsgaii.objectivefunction.*;
import top.lazyr.refactor.algorithm.genetic.nsgaii.plugin.childpopulationproducer.ChildPopulationProducer;
import top.lazyr.refactor.algorithm.genetic.nsgaii.plugin.childpopulationproducer.RefactorChildPopulationProducer;
import top.lazyr.refactor.algorithm.genetic.nsgaii.plugin.geneticcodeproducer.GeneticCodeProducer;
import top.lazyr.refactor.algorithm.genetic.nsgaii.plugin.geneticcodeproducer.RefactorProducer;
import top.lazyr.refactor.algorithm.genetic.nsgaii.plugin.populationproducer.PopulationProducer;
import top.lazyr.refactor.algorithm.genetic.nsgaii.plugin.populationproducer.RefactorPopulationProducer;
import top.lazyr.refactor.algorithm.genetic.nsgaii.selector.ChampionshipSelector;
import top.lazyr.refactor.algorithm.genetic.nsgaii.selector.CrossoverParticipantCreator;
import top.lazyr.refactor.algorithm.genetic.nsgaii.termination.MyTermination;
import top.lazyr.refactor.algorithm.genetic.nsgaii.termination.TerminatingCriterion;
import top.lazyr.refactor.generator.*;
import top.lazyr.script.model.ExpParam;
import top.lazyr.script.service.ExpParamService;
import top.lazyr.smell.detector.cyclicdependency.CyclicDependencyDetector;
import top.lazyr.smell.detector.hublikedependency.HubLikeDependencyDetector;
import top.lazyr.smell.detector.unstabledependency.UnstableDependencyDetector;
import top.lazyr.util.FileUtil;
import top.lazyr.util.ObjectiveUtil;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * @author lazyr
 * @created 2022/2/16
 */
public class Nsga2Exp {

    public static void exp(String configFilePath) {
        List<ExpParam> unFinishedExpParams = ExpParamService.getUnFinishedExpParams(configFilePath);
        for (ExpParam unFinishedExpParam : unFinishedExpParams) {
            Date date = new Date();
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            // ?????????????????????????????????
            String outputCatalog = createOutputCatalog(dateFormat.format(date), unFinishedExpParam);
            // ????????????????????????????????????????????????
            FileUtil.createCatalog(outputCatalog);
            // ????????????????????????????????????????????????????????????????????????
            int finishedExpTime = ExpParamService.clear(outputCatalog);

            Long start = System.currentTimeMillis();
            refactor(unFinishedExpParam, finishedExpTime + 1, outputCatalog);
            Long end = System.currentTimeMillis();
            System.out.println("??????: " + ((end - start) / (1000 * 60)) + "???");
            ExpParamService.finishExpParam(unFinishedExpParam, finishedExpTime + 1, outputCatalog);

        }

    }

    private static void refactor(ExpParam expParam, int currentExpTime, String outputCatalog) {
        // 1??????????????????????????????
        SourceCodeParser sourceCodeParser = new SourceCodeParser();
        Graph originGraph = sourceCodeParser.parse(expParam.getProjectPath());
        // ??????????????????
        StringBuilder configurationInfo = new StringBuilder();
        // 2?????????????????????
        int originHLNum = 0;
        int originUDNum = 0;
        int originCDNum = 0;
        int totalSmellNum = 0;
        int totalSmellComponentNum = 0;
        int totalSmellFileNum = 0;
        Set<Node> smellComponentNodes = new HashSet<>(); // ????????????
        configurationInfo.append(ConsolePrinter.createTitle("????????????") + "\r\n");
        if (expParam.isOptimizedHL()) {
            Set<Node> originHLNodes = HubLikeDependencyDetector.detect(originGraph).keySet();
            smellComponentNodes.addAll(originHLNodes);
            originHLNum = originHLNodes.size();
            configurationInfo.append("???????????????: " + originHLNum + "\r\n");
        }
        if (expParam.isOptimizedUD()) {
            Set<Node> originUDNodes = UnstableDependencyDetector.detect(originGraph).keySet();
            smellComponentNodes.addAll(originUDNodes);
            originUDNum = originUDNodes.size();
            configurationInfo.append("???????????????: " + originUDNum + "\r\n");
        }
        if (expParam.isOptimizedCD()) {
            Set<Node> originCDNodes = new HashSet<>(CyclicDependencyDetector.detect(originGraph));
            smellComponentNodes.addAll(originCDNodes);
            originCDNum = originCDNodes.size();
            configurationInfo.append("???????????????: " + originCDNum + "\r\n");
        }
        totalSmellNum = originHLNum + originUDNum + originCDNum;
        totalSmellComponentNum = smellComponentNodes.size();
        totalSmellFileNum = findSubFiles(originGraph, smellComponentNodes);
        configurationInfo.append("???????????????: " + totalSmellNum + "\r\n");
        configurationInfo.append("??????????????????: " + totalSmellComponentNum + "\r\n");
        configurationInfo.append("??????????????????: " + totalSmellFileNum + "\r\n");
        // 3???????????????????????????????????????
        RefactorListGenerator refactorListGenerator = null; // ???????????????????????????
        switch (expParam.getPlanNum()) {
            case 1:
                refactorListGenerator = new RefactorListGenerator1(originGraph, new ArrayList<>(smellComponentNodes));
                break;
            case 2:
                refactorListGenerator = new RefactorListGenerator2(originGraph, new ArrayList<>(smellComponentNodes));
                break;
            case 3:
                refactorListGenerator = new RefactorListGenerator3(originGraph, new ArrayList<>(smellComponentNodes));
                break;
            case 4:
                refactorListGenerator = new RefactorListGenerator4(originGraph, new ArrayList<>(smellComponentNodes));
                break;
            default:
                System.out.println("????????????????????????????????????, ????????????????????????????????????????????????");
                return;
        }
        configurationInfo.append(ConsolePrinter.createTitle("??????&&????????????") + "\r\n");
        // ??????????????????
        List<AbstractObjectiveFunction> objectiveFunctions = new ArrayList<>();
        // ?????????????????????????????????
        objectiveFunctions.add(new SmellEliminationRate(totalSmellNum, expParam.isOptimizedHL(), expParam.isOptimizedUD(), expParam.isOptimizedCD()));

        // ??????????????????????????????
        if (expParam.isOptimizedCohesion()) {
            double originCohesion = ObjectiveUtil.calCohesion(originGraph);
            objectiveFunctions.add(new Cohesion1(originCohesion));
            configurationInfo.append("????????????: " + originCohesion + "\r\n");
        }
        // ??????????????????????????????
        if (expParam.isOptimizedCoupling()) {
            double originCoupling = ObjectiveUtil.calCoupling(originGraph);
            objectiveFunctions.add(new Coupling1(originCoupling));
            configurationInfo.append("????????????: " + originCoupling + "\r\n");
        }
        if (!(expParam.isOptimizedCohesion() || expParam.isOptimizedCoupling())) { // ????????????????????????????????????
            objectiveFunctions.add(new Placeholder());
            configurationInfo.append("????????????: 0\r\n");
        }
        Configuration configuration = new Configuration(objectiveFunctions);
        // ????????????
        // ?????????????????????
        PopulationProducer populationProducer = new RefactorPopulationProducer(originGraph); // ?????????????????????
        GeneticCodeProducer geneticCodeProducer = new RefactorProducer(refactorListGenerator); // ??????????????????
        ChildPopulationProducer childPopulationProducer = new RefactorChildPopulationProducer(originGraph); // ???????????????
        configuration.setPopulationProducer(populationProducer);
        configuration.setGeneticCodeProducer(geneticCodeProducer);
        configuration.setChildPopulationProducer(childPopulationProducer);
        // ????????????
        configuration.setPopulationSize(expParam.getPopulationSize()); // ????????????
        configuration.setMaxGenerations(expParam.getMaxGenerationCount()); // ????????????
        configuration.setChromosomeLength((int) (expParam.getChromosomeLengthRatio() * totalSmellFileNum)); // ???????????????
        // ????????????
        CrossoverParticipantCreator selector = new ChampionshipSelector(); // ????????????: ?????????????????????
        AbstractCrossover crossover = new SingleCrossover(selector, expParam.getCrossoverProbability()); // ?????????????????????????????????
        AbstractMutation mutation = new SingleMutation(expParam.getMutationProbability(), refactorListGenerator); // ???????????????????????????
        configuration.setCrossover(crossover);
        configuration.setMutation(mutation);
        // ??????????????????
        TerminatingCriterion terminatingCriterion = new MyTermination(configuration); // ?????????????????????????????????????????????
        configuration.setTerminatingCriterion(terminatingCriterion);
        // ??????????????????????????????????????????
        configuration.setOutputCatalog(outputCatalog);
        configuration.setCurrentExpTime(currentExpTime);
        configuration.setOptimizedHL(expParam.isOptimizedHL());
        configuration.setOptimizedUD(expParam.isOptimizedUD());
        configuration.setOptimizedCD(expParam.isOptimizedCD());


        configurationInfo.append(ConsolePrinter.createTitle("NSGA2????????????") + "\r\n");
        configurationInfo.append("crossoverProbability : " + expParam.getCrossoverProbability() + "\r\n");
        configurationInfo.append("mutationProbability : " + expParam.getMutationProbability() + "\r\n");


        configurationInfo.append(configuration.toString());

        TxtOutput.write2File(outputCatalog + "??????????????????.txt", configurationInfo.toString());

        System.out.println(configurationInfo.toString()); // ??????????????????

        // ??????NSGA2?????????????????????
        NSGA2 nsga2 = new NSGA2(configuration);
        nsga2.run();
    }

    private static String createOutputCatalog(String day, ExpParam expParam) {
        String optimizationObjectives = (expParam.isOptimizedHL() ? "hl_" : "") +
                (expParam.isOptimizedUD() ? "ud_" : "") +
                (expParam.isOptimizedCD() ? "cd_" : "") +
                (expParam.isOptimizedCohesion() ? "cohesion_" : "") +
                (expParam.isOptimizedCoupling() ? "coupling_" : "");
        optimizationObjectives = StrUtil.strip(optimizationObjectives, "_");
        String outputCatalog = day + "/" + expParam.getProjectName() + "/" + optimizationObjectives +
                "/plan" + expParam.getPlanNum() + "/" +
                expParam.getPopulationSize() + "_" +
                expParam.getCrossoverProbability() + "_" +
                expParam.getMutationProbability() + "_" +
                expParam.getChromosomeLengthRatio() + "/";
        return outputCatalog;
    }




    /**
     * ????????????componentNodes????????????Node??????
     * @param componentNodes
     * @return
     */
    private static int findSubFiles(Graph graph, Set<Node> componentNodes) {
        int subFileNum  = 0;
        for (Node componentNode : componentNodes) {
            List<Node> subFileNodes = GraphManager.findSubFileNodes(graph, componentNode);
            subFileNum += subFileNodes == null ? 0 : subFileNodes.size();
        }
        return subFileNum;
    }



}
