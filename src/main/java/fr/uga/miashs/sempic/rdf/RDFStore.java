/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package fr.uga.miashs.sempic.rdf;

import fr.uga.miashs.sempic.model.rdf.SempicOnto;
import fr.uga.miashs.sempic.rdf.Namespaces;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.ejb.Stateless;
import javax.ejb.LocalBean;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.ontology.OntModelSpec;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionFactory;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.modify.request.QuadAcc;
import org.apache.jena.sparql.modify.request.UpdateDeleteWhere;
import org.apache.jena.update.Update;
import org.apache.jena.vocabulary.RDFS;

/**
 *
 * @author martin
 */
@Stateless
@LocalBean
public class RDFStore {

    public final static String ENDPOINT_QUERY = "http://localhost:3030/sempic/sparql"; // SPARQL endpoint
    public final static String ENDPOINT_UPDATE = "http://localhost:3030/sempic/update"; // SPARQL UPDATE endpoint
    public final static String ENDPOINT_GSP = "http://localhost:3030/sempic/data"; // Graph Store Protocol

    protected final RDFConnection cnx;

    public RDFStore() {
        cnx = RDFConnectionFactory.connect(ENDPOINT_QUERY, ENDPOINT_UPDATE, ENDPOINT_GSP);
    }

    /**
     * Save the given model into the triple store.
     *
     * @param m THe Jena model to be persisted
     */
    public void saveModel(Model m) {
        cnx.begin(ReadWrite.WRITE);
        cnx.load(m);
        cnx.commit();
    }

    /**
     * Delete the given model from the triple store. Be carreful: Blank nodes
     * are replaced by variables
     *
     * @param m the model to be deleted
     */
    public void deleteModel(Model m) {
        HashMap<Resource, Var> map = new HashMap<>();
        QuadAcc acc = new QuadAcc();
        m.listStatements().forEachRemaining(st -> {
            if (st.getSubject().isAnon() || st.getObject().isAnon()) {
                Node s = blankNodeAsVariable(st.getSubject(), map);
                Node p = st.getPredicate().asNode();
                Node o = blankNodeAsVariable(st.getObject(), map);

                acc.addTriple(new Triple(s, p, o));
            } else {

                acc.addTriple(st.asTriple());
            }
        });

        Update u = new UpdateDeleteWhere(acc);
        //System.out.println(u);
        cnx.begin(ReadWrite.WRITE);
        cnx.update(u);
        cnx.commit();
    }

    private Node blankNodeAsVariable(Resource r, Map<Resource, Var> bnMap) {
        if (r.isAnon()) {
            Var v = bnMap.get(r);
            if (v == null) {
                bnMap.put(r, v = Var.alloc("A" + bnMap.size()));
            }
            return v;
        }
        return r.asNode();
    }

    private Node blankNodeAsVariable(RDFNode r, Map<Resource, Var> bnMap) {
        if (r.isAnon()) {
            return blankNodeAsVariable(r.asResource(), bnMap);
        }
        return r.asNode();
    }

    /**
     * Delete all the statements where the resource appears as subject or object
     *
     * @param r The named resource to be deleted (the resource cannot be
     * annonymous)
     */
    public void deleteResource(Resource r) {
        if (r.isURIResource()) {
            cnx.begin(ReadWrite.WRITE);
            cnx.update("DELETE WHERE { <" + r.getURI() + "> ?p ?o }");
            cnx.update("DELETE WHERE { ?s ?p <" + r.getURI() + "> }");
            cnx.commit();
        }
    }

    /**
     * Retieves all the resources that are subclasses of resource c. To be
     * selected classes must have the property rdfs:label instanciated
     *
     * @param c A named class (the resource cannot be annonymous)
     * @return
     */
    public List<Resource> listSubClassesOf(Resource c) {
        String query
                = "CONSTRUCT { "
                + "?s <" + RDFS.label + "> ?o "
                + "} WHERE {"
                + "?s <" + RDFS.subClassOf + "> <" + c.getURI() + "> ."
                + "?s <" + RDFS.label + "> ?o ."
                + "} ORDER BY ASC(?o)";
		System.out.println(query);
        Model m = cnx.queryConstruct(query);
//		System.out.println(m);
        return m.listSubjects().toList();
    }

    /**
     * Retieves all instances of a class. To be selected classes must have the
     * property rdfs:label instanciated
     *
     * @param c A named class (the resource cannot be annonymous)
     * @return
     */
    public List<Resource> listInstancesOf(Resource c) {
        String query
                = "CONSTRUCT { ?s <" + RDFS.label + "> ?l . }"
                + "WHERE {"
                + "?s a <" + c.getURI() + "> ;"
                + "<" + RDFS.label + "> ?l "
                + "}";
		System.out.println(query);
        Model m = cnx.queryConstruct(query);
//		System.out.println(m);
        return m.listSubjects().toList();
    }

    public List<Resource> createAnonInstances(List<Resource> classes) {
        Model m = ModelFactory.createDefaultModel();
        List<Resource> res = new ArrayList<>();
        for (Resource c : classes) {
            Resource instance = m.createResource(c);
            instance.addLiteral(RDFS.label, "a " + c.getProperty(RDFS.label).getLiteral());
            res.add(instance);
        }
        return res;
    }

    public Resource createPhoto(long photoId, long albumId, long ownerId) {
        // create an empty RDF graph
        Model m = ModelFactory.createDefaultModel();
        // create an instance of Photo in Model m
        Resource pRes = m.createResource(Namespaces.getPhotoUri(photoId), SempicOnto.Photo);

        pRes.addLiteral(SempicOnto.albumId, albumId);
        pRes.addLiteral(SempicOnto.ownerId, ownerId);

        saveModel(m);

        return pRes;
    }

    /**
     * Query a Photo and retrieve all the direct properties of the photo and if
     * the property are depic, takenIn or takenBy, it also retrieve the labels
     * of the object of these properties
     *
     * @param id
     * @return
     */
    public Resource readPhoto(long id) {
        String pUri = Namespaces.getPhotoUri(id);

        String s = "CONSTRUCT {"
                + "<" + pUri + "> ?p ?o . "
                + "<" + pUri + "> ?p1 ?o1 . "
                + "?o1 <" + RDFS.label + "> ?o2 . "
                + "} "
                + "WHERE { "
                + "<" + pUri + "> ?p ?o . "
                + "OPTIONAL {"
                + "<" + pUri + "> ?p1 ?o1 ."
                + "?o1 <" + RDFS.label + "> ?o2 ."
                + "FILTER (?p1 IN (<" + SempicOnto.depicts + ">,<" + SempicOnto.takenIn + ">,<" + SempicOnto.takenBy + ">)) "
                + "}"
                + "}";
		System.out.println("Here in readPhoto in RDFStore "+s);
        Model m = cnx.queryConstruct(s);
//		System.out.println("Here in readPhoto in RDFStore "+m);

        return m.getResource(pUri);
    }

    public RDFConnection getCnx() {
        return cnx;
    }

    public Model getPhotoModel(long id) {

        String pUri = Namespaces.getPhotoUri(id);

        String s = "CONSTRUCT {"
                + "<" + pUri + "> ?p ?o . "
                + "<" + pUri + "> ?p1 ?o1 . "
                + "?o1 <" + RDFS.label + "> ?o2 . "
                + "} "
                + "WHERE { "
                + "<" + pUri + "> ?p ?o . "
                + "OPTIONAL {"
                + "<" + pUri + "> ?p1 ?o1 ."
                + "?o1 <" + RDFS.label + "> ?o2 ."
                + "FILTER (?p1 IN (<" + SempicOnto.depicts + ">,<" + SempicOnto.takenIn + ">,<" + SempicOnto.takenBy + ">)) "
                + "}"
                + "}";
        Model m = cnx.queryConstruct(s);

        return m;

    }

    public static String getStatementHash(Statement stmt) {
        String uri = stmt.getObject().toString();
        return uri.substring(uri.lastIndexOf("#") + 1);
    }

    public static void main(String[] args) {
        RDFStore s = new RDFStore();

        Model m = ModelFactory.createDefaultModel();
//		Resource photoRes = m.createResource(Namespaces.getPhotoUri(1551), SempicOnto.Photo);
        Resource photoRes = s.readPhoto(1751);
//		Model m = s.getPhotoModel(1751);
        System.out.println(photoRes.getPropertyResourceValue(SempicOnto.inThePicture));
        StmtIterator itr;
        itr = photoRes.listProperties(SempicOnto.inThePicture);
        while (itr.hasNext()) {
            Statement stmt = itr.next();
            String uri = stmt.getObject().toString();
            System.out.println(uri.substring(uri.lastIndexOf("#") + 1));
        }

//		itr = photoRes.listProperties(SempicOnto.inThePicture);
//		rdfResources += "<h3>inThePicture</h3>";
//		while(itr.hasNext())
//		{
////			rdfResources += itr.next().getProperty(RDFS.label).getLiteral().toString()+"<br/>";
//			rdfResources += itr.next().toString()+"<br/>";
//		}
//		System.out.println("Here : "+photoRes.getProperty(SempicOnto.takenBy).getObject());
//		System.out.println("Here : "+photoRes.getProperty(SempicOnto.depicts).getProperty(RDFS.label).getLiteral().toString());
//        Resource pRes = s.createPhoto(1, 1, 1);
//
//        Model m = ModelFactory.createDefaultModel();
//        Resource newAnimal = m.createResource(SempicOnto.Animal);
//        newAnimal.addLiteral(RDFS.label, "Medor");
////        m.add(pRes, SempicOnto.depicts, newAnimal);
//        m.write(System.out, "turtle");
//
//        //s.deleteModel(m);
//        //s.cnx.load(m);
//        List<Resource> classes = s.listSubClassesOf(SempicOnto.Depiction);
//        classes.forEach(c -> {System.out.println(c);});
//
//        List<Resource> instances = s.createAnonInstances(classes);
//        instances.forEach(i -> {
//            System.out.println(i.getProperty(RDFS.label));
//        });
//        s.readPhoto(1551).getModel().write(System.out,"turtle");
        // print the graph on the standard output
        //pRes.getModel().write(System.out);
    }
}
