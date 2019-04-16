package org.reactome.tcrd.test;


import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.persistence.TypedQuery;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.Test;
import org.reactome.r3.util.FileUtility;
import org.reactome.r3.util.InteractionUtilities;
import org.reactome.tcrd.model.Activity;
import org.reactome.tcrd.model.ChEMBLActivity;
import org.reactome.tcrd.model.DrugActivity;
import org.reactome.tcrd.model.Protein;
import org.reactome.tcrd.model.Target;

public class HibernateTests {
    
    public HibernateTests() {
    }
    
    @Test
    public void testTargetTypes() throws Exception {
        Map<String, Set<String>> humanToZebra = loadHumanToZebraMap();
        System.out.println("\nSize of human to zebra: " + humanToZebra.size());
        
        SessionFactory sessionFactory = createSessionFactory();
        Session session = sessionFactory.openSession();
        
        TypedQuery<Target> query = session.createQuery("FROM " + Target.class.getName(), Target.class);
        List<Target> targets = query.getResultList();
        System.out.println("Total targets: " + targets.size());
        
        // Check ion channels
        List<Target> channels = targets.stream()
                .filter(t -> t.getFamily() != null && t.getFamily().equals("IC"))
                .collect(Collectors.toList());
        System.out.println("Total channels: " + channels.size());
        System.out.println("Protein\tUniProt\tGene\tTargetLevel\tMappedToZebrafish");
        channels.forEach(c -> {
            Protein protein = c.getProtein();
            Set<String> zebrafishSet = humanToZebra.get(protein.getUniprot());
            System.out.println(c.getName() + "\t" +
                               protein.getUniprot() + "\t" + 
                               protein.getSym() + "\t" + 
                               c.getTargetDevLevel() + "\t" +
                               (zebrafishSet == null ? "NA" : String.join(",", zebrafishSet)));
        });
        
        // Dark channels
        Map<String, Set<Target>> tdlToTargets = new HashMap<>();
        channels.forEach(c -> {
            tdlToTargets.compute(c.getTargetDevLevel(), (key, set) -> {
                if (set == null)
                    set = new HashSet<>();
                set.add(c);
                return set;
            });
        });
        System.out.println("\nTarget levels: ");
        tdlToTargets.forEach((tdl, set) -> {
            System.out.println(tdl + "\t" + set.size());
        });
        Set<Target> darkChannels = tdlToTargets.get("Tdark");
        System.out.println("Dark Channels: " + darkChannels.size());
        darkChannels.forEach(t -> System.out.println(t.getName() + "\t" + t.getProtein().getSym()));
        
        session.close();
    }
    
    private Map<String, Set<String>> loadHumanToZebraMap() throws IOException {
        Map<String, Set<String>> zebraToHuman = loadToHumanMapInUniProt("7955");
        return InteractionUtilities.switchKeyValues(zebraToHuman);
    }
    
    private Map<String, Set<String>> loadToHumanMapInUniProt(String taxonId) throws IOException {
        FileUtility fu = new FileUtility();
        // This file was generated in FINetworkBuild project by modifying some code there.
        String fileName = "/Users/wug/datasets/Ensembl/release_91/ProteinFamilies_Zebrafish.txt";
        Map<String, Set<String>> familyToProteins = fu.loadSetMap(fileName);
        Set<String> humanIds = new HashSet<String>();
        Set<String> otherIds = new HashSet<String>();
        // To be returned
        Map<String, Set<String>> map = new HashMap<String, Set<String>>();
        for (String family : familyToProteins.keySet()) {
            Set<String> proteins = familyToProteins.get(family);
            humanIds.clear();
            otherIds.clear();
            splitIds(proteins, humanIds, otherIds, taxonId);
            for (String otherId : otherIds) {
                Set<String> humanSet = map.get(otherId);
                if (humanSet == null) {
                    humanSet = new HashSet<String>();
                    map.put(otherId, humanSet);
                }
                humanSet.addAll(humanIds);
            }
        }
        return map;
    }
    
    private void splitIds(Set<String> proteins,
                          Set<String> humanIds,
                          Set<String> otherIds,
                          String taxonId) {
        for (String protein : proteins) {
            if (protein.startsWith("9606:")) {
                // This is a human protein
                humanIds.add(protein.substring(5));
            }
            else if (protein.startsWith(taxonId)) {
                otherIds.add(protein.substring(taxonId.length() + 1)); // 1 for ":".
            }
        }
    }
    
    @Test
    public void testQueryProtein() throws Exception {
        SessionFactory sessionFactory = createSessionFactory();
        Session session = sessionFactory.openSession();
        
        String gene = "CHEK2";
        
        TypedQuery<Protein> query = session.createQuery("SELECT p FROM " + Protein.class.getSimpleName() + " p WHERE p.sym = :gene", Protein.class);
        query.setParameter("gene", gene);
        Protein egfr = query.getSingleResult();
        Target target = egfr.getTarget();
        Set<ChEMBLActivity> chemblActivities = target.getChemblActivities();
        System.out.println("\nChEMBL:");
        chemblActivities.forEach(a -> {
            double activity = a.getActivityValue();
            double binding = Math.pow(10.0, -activity);
            System.out.println(a.getCompoundChEMBLNameInRef() + "\t" + a.getActivityType() + "\t" + activity + "\t" + binding);
        });
        Set<DrugActivity> drugActivities = target.getDrugActivities();
        System.out.println("\nDrug:");
        drugActivities.forEach(a -> {
            Double activity = a.getActivityValue();
            Double binding = null;
            if (activity != null)
                binding = Math.pow(10.0, -activity);
            System.out.println(a.getDrug() + "\t" + a.getActionType() + "\t" + a.getActivityValue() + "\t" + activity + "\t" + binding);
        });
        
        session.close();
        sessionFactory.close();
    }

    private SessionFactory createSessionFactory() {
        StandardServiceRegistry standardRegistry = new StandardServiceRegistryBuilder().configure("hibernate.cfg.xml").build();
        Metadata metaData = new MetadataSources(standardRegistry).getMetadataBuilder().build();
        SessionFactory sessionFactory = metaData.getSessionFactoryBuilder().build();
        return sessionFactory;
    }
    
    /**
     * Just a bunch of tests to ensure the Hibernate annotation works as expected.
     * @throws Exception
     */
    @Test
    public void testLoad() throws Exception {
        SessionFactory sessionFactory = createSessionFactory();
        Session session = sessionFactory.openSession();
        
        // Check proteins
        TypedQuery<Protein> query = session.createQuery("FROM " + Protein.class.getName(), Protein.class);
        List<Protein> proteins = query.getResultList();
        System.out.println("Total proteins: " + proteins.size());
        Protein protein = proteins.stream().findAny().get();
        System.out.println("Protein: " + protein.getUniprot() + ", " + protein.getSym());
        Target target = protein.getTarget();
        System.out.println("Protein's target: " + target.getName() + ", " + target.getTargetDevLevel());
        
        // Check targets
        TypedQuery<Target> targetQuery = session.createQuery("FROM " + Target.class.getName(), Target.class);
        List<Target> targets = targetQuery.getResultList();
        System.out.println("\nTotal targets: " + targets.size());
        target = targets.stream().filter(t -> t.getChemblActivities().size() > 0).findAny().get();
        System.out.println("Target: " + target.getName() + ", " + target.getTargetDevLevel());
        protein = target.getProtein();
        System.out.println("Target's protein: " + protein.getUniprot() + ", " + protein.getSym());
        Set<ChEMBLActivity> chemblActivities = target.getChemblActivities();
        System.out.println("Total ChEMBLActivities: " + chemblActivities.size());
        ChEMBLActivity chemblActivity = chemblActivities.stream().findAny().get();
        if (chemblActivity != null) {
            System.out.println("One of these activites: " + 
                    chemblActivity.getCompoundChEMBLId() + ", " + 
                    chemblActivity.getActivityType() + ", " + 
                    chemblActivity.getActivityValue());
        }
        target = targets.stream().filter(t -> t.getDrugActivities().size() > 0).findAny().get();
        System.out.println("Another target: " + target.getName() + ", " + target.getTargetDevLevel());
        Collection<DrugActivity> drugActivities = target.getDrugActivities();
        System.out.println("Total drug activities: " + drugActivities);
        DrugActivity drugActivity = drugActivities.stream().findAny().get();
        if (drugActivity != null) {
            System.out.println("One of drug activities: " + 
                    drugActivity.getDrug() + ", " + 
                    drugActivity.getActionType() + ", " + 
                    drugActivity.getActivityValue());
        }
        
        // Check ChEBMLActivity
        TypedQuery<ChEMBLActivity> chemblActivityQuery = session.createQuery("FROM " + ChEMBLActivity.class.getName(), ChEMBLActivity.class);
        List<ChEMBLActivity> activities = chemblActivityQuery.getResultList();
        System.out.println("\nTotal ChEMBLActivities: " + activities.size());
        Activity activity = activities.stream().findAny().get();
        System.out.println("ChEMBLActivity: " + activity.getActivityType() + ", " + activity.getActivityValue());
        target = activity.getTarget();
        System.out.println("Its target: " + target.getName() + ", " + target.getTargetDevLevel());
        
        // Check DrugActivity
        TypedQuery<DrugActivity> drugActivityQuery = session.createQuery("FROM " + DrugActivity.class.getName(), DrugActivity.class);
        drugActivities = drugActivityQuery.getResultList();
        System.out.println("\nTotal DrugActivities: " + drugActivities.size());
        drugActivity = drugActivities.stream().findAny().get();
        System.out.println("One of DrugActivity: " + drugActivity.getDrug() + ", " +
                drugActivity.getActionType() + ", " +
                drugActivity.getActivityValue() + ", " +
                drugActivity.getNlmDrugInfo());
        target = drugActivity.getTarget();
        System.out.println("Its target: " + target.getName() + ", " + target.getTargetDevLevel());
        
        session.close();
        sessionFactory.close();
    }

}
