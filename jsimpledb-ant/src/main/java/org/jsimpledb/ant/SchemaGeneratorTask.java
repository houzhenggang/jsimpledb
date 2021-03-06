
/*
 * Copyright (C) 2015 Archie L. Cobbs. All rights reserved.
 */

package org.jsimpledb.ant;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;

import org.apache.tools.ant.AntClassLoader;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.types.Reference;
import org.apache.tools.ant.types.Resource;
import org.jsimpledb.DefaultStorageIdGenerator;
import org.jsimpledb.JSimpleDBFactory;
import org.jsimpledb.StorageIdGenerator;
import org.jsimpledb.annotation.JFieldType;
import org.jsimpledb.annotation.JSimpleClass;
import org.jsimpledb.core.Database;
import org.jsimpledb.core.FieldType;
import org.jsimpledb.kv.simple.SimpleKVDatabase;
import org.jsimpledb.schema.SchemaModel;
import org.jsimpledb.spring.JSimpleDBClassScanner;
import org.jsimpledb.spring.JSimpleDBFieldTypeScanner;

/**
 * Ant task for schema XML generation and/or verification.
 *
 * <p>
 * This task scans the configured classpath for classes with {@link org.jsimpledb.annotation.JSimpleClass &#64;JSimpleClass}
 * and {@link org.jsimpledb.annotation.JFieldType &#64;JFieldType} annotations and either writes the generated schema
 * to an XML file, or verifies the schema matches an existing XML file.
 *
 * <p>
 * Generation of schema XML files and the use of this task is not necessary. However, it does allow certain
 * schema-related problems to be detected at build time instead of runtime. In particular, it can let you know
 * if any change to your model classes requires a new JSimpleDB schema version.
 *
 * <p>
 * This task can also check for conflicts between the schema in question and older schema versions that may still
 * exist in production databases. These other schema versions are specified using nested {@code <oldschemas>}
 * elements, which work just like {@code <fileset>}'s.
 *
 * <p>
 * The following attributes are supported by this task:
 *
 * <div style="margin-left: 20px;">
 * <table border="1" cellpadding="3" cellspacing="0" summary="Supported Tasks">
 * <tr style="bgcolor:#ccffcc">
 *  <th align="left">Attribute</th>
 *  <th align="left">Required?</th>
 *  <th align="left">Description</th>
 * </tr>
 * <tr>
 *  <td>{@code mode}</td>
 *  <td>No</td>
 *  <td>
 *      <p>
 *      Set to {@code generate} to generate a new XML file, or {@code verify} to verify an existing XML file.
 *      </p>
 *
 *      <p>
 *      Default is {@code verify}.
 *      </p>
 * </td>
 * </tr>
 * <tr>
 *  <td>{@code file}</td>
 *  <td>Yes</td>
 *  <td>
 *      <p>
 *      The XML file to generate or verify.
 *      </p>
 * </td>
 * </tr>
 * <tr>
 *  <td>{@code matchNames}</td>
 *  <td>No</td>
 *  <td>
 *      <p>
 *      Whether to verify not only {@link org.jsimpledb.schema.ScheamModel#isCompatibleWith "same version"
 *      schema compatibility} but also that the two schemas are actually identical, i.e.,
 *      the same names are used for object types, fields, and composite indexes, and non-structural
 *      attributes such as delete cascades have not changed.
 *      </p>
 *
 *      <p>
 *      Two schemas that are equivalent except for names are compatible, because the core API uses storage ID's,
 *      not names. However, if names change then some JSimpleDB layer operations, such as index queries
 *      and reference path inversion, may need to be updated.
 *      </p>
 *
 *      <p>
 *      Default is {@code true}. Ignored unless {@code mode} is {@code verify}.
 *      </p>
 * </td>
 * </tr>
 * <tr>
 *  <td>{@code failOnError}</td>
 *  <td>No</td>
 *  <td>
 *      <p>
 *      Whether to fail if verification fails when {@code mode="verify"} or when older schema
 *      versions are specified using nested {@code <oldschemas>} elements, which work just like
 *      {@code <fileset>}s.
 *      </p>
 *
 *      <p>
 *      Default is {@code true}.
 *      </p>
 * </td>
 * </tr>
 * <tr>
 *  <td>{@code verifiedProperty}</td>
 *  <td>No</td>
 *  <td>
 *      <p>
 *      The name of an ant property to set to {@code true} or {@code false} depending on whether
 *      verification succeeded or failed. Useful when {@code failOnError} is set to {@code false}
 *      and you want to handle the failure elsewhere in the build file.
 *      </p>
 *
 *      <p>
 *      Default is to not set any property.
 *      </p>
 * </td>
 * </tr>
 * <tr>
 *  <td>{@code classpath} or {@code classpathref}</td>
 *  <td>Yes</td>
 *  <td>
 *      <p>
 *      Specifies the search path containing classes with {@link org.jsimpledb.annotation.JSimpleClass &#64;JSimpleClass}
 *      and {@link org.jsimpledb.annotation.JFieldType &#64;JFieldType} annotations.
 *      </p>
 * </td>
 * </tr>
 * <tr>
 *  <td>{@code packages}</td>
 *  <td>Yes, unless {@code classes} are specified</td>
 *  <td>
 *      <p>
 *      Specifies one or more Java package names (separated by commas and/or whitespace) under which to look
 *      for classes with {@link org.jsimpledb.annotation.JSimpleClass &#64;JSimpleClass}
 *      or {@link org.jsimpledb.annotation.JFieldType &#64;JFieldType} annotations.
 *
 *      <p>
 *      Use of this attribute requires Spring's classpath scanning classes ({@code spring-context.jar});
 *      these must be on the {@code <taskdef>} classpath.
 *      </p>
 * </td>
 * </tr>
 * <tr>
 *  <td>{@code classes}</td>
 *  <td>Yes, unless {@code packages} are specified</td>
 *  <td>
 *      <p>
 *      Specifies one or more Java class names (separated by commas and/or whitespace) of
 *      classes with {@link org.jsimpledb.annotation.JSimpleClass &#64;JSimpleClass}
 *      or {@link org.jsimpledb.annotation.JFieldType &#64;JFieldType} annotations.
 *      </p>
 * </td>
 * </tr>
 * <tr>
 *  <td>{@code storageIdGeneratorClass}</td>
 *  <td>No</td>
 *  <td>
 *      <p>
 *      Specifies the name of an optional custom {@link StorageIdGenerator} class.
 *      </p>
 *
 *      <p>
 *      By default, a {@link DefaultStorageIdGenerator} is used.
 *      </p>
 * </td>
 * </tr>
 * </table>
 * </div>
 *
 * <p>
 * Classes are found by scanning the packages listed in the {@code "packages"} attribute.
 * Alternatively, or in addition, specific classes may specified using the {@code "classes"} attribute.
 *
 * <p>
 * To install this task into ant:
 *
 * <pre>
 *  &lt;project xmlns:jsimpledb="urn:org.jsimpledb.ant" ... &gt;
 *      ...
 *      &lt;taskdef uri="urn:org.jsimpledb.ant" name="schema"
 *        classname="org.jsimpledb.ant.SchemaGeneratorTask" classpathref="jsimpledb.classpath"/&gt;
 * </pre>
 *
 * <p>
 * Example of generating a schema XML file that corresponds to the specified Java model classes:
 *
 * <pre>
 *  &lt;jsimpledb:schema mode="generate" classpathref="myclasses.classpath"
 *    file="schema.xml" packages="com.example.model"/&gt;
 * </pre>
 *
 * <p>
 * Example of verifying that the schema generated from the Java model classes has not
 * changed incompatibly (i.e., in a way that would require a new schema version):
 *
 * <pre>
 *  &lt;jsimpledb:schema mode="verify" classpathref="myclasses.classpath"
 *    file="expected-schema.xml" packages="com.example.model"/&gt;
 * </pre>
 *
 * <p>
 * Example of doing the same thing, and also verifying the generated schema is compatible with prior schema versions
 * that may still be in use in production databases:
 *
 * <pre>
 *  &lt;jsimpledb:schema mode="verify" classpathref="myclasses.classpath"
 *    file="expected-schema.xml" packages="com.example.model"&gt;
 *      &lt;jsimpledb:oldschemas dir="obsolete-schemas" includes="*.xml"/&gt;
 *  &lt;/jsimpledb:schema&gt;
 * </pre>
 *
 * @see org.jsimpledb.JSimpleDB
 * @see org.jsimpledb.schema.SchemaModel
 */
public class SchemaGeneratorTask extends Task {

    public static final String MODE_VERIFY = "verify";
    public static final String MODE_GENERATE = "generate";

    private String mode = MODE_VERIFY;
    private boolean matchNames = true;
    private boolean failOnError = true;
    private String verifiedProperty;
    private File file;
    private Path classPath;
    private String storageIdGeneratorClassName = DefaultStorageIdGenerator.class.getName();
    private final ArrayList<OldSchemas> oldSchemasList = new ArrayList<>();
    private final LinkedHashSet<String> classes = new LinkedHashSet<>();
    private final LinkedHashSet<String> packages = new LinkedHashSet<>();

    public void setClasses(String classes) {
        this.classes.addAll(Arrays.asList(classes.split("[\\s,]+")));
    }

    public void setPackages(String packages) {
        this.packages.addAll(Arrays.asList(packages.split("[\\s,]+")));
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public void setMatchNames(boolean matchNames) {
        this.matchNames = matchNames;
    }

    public void setFailOnError(boolean failOnError) {
        this.failOnError = failOnError;
    }

    public void setVerifiedProperty(String verifiedProperty) {
        this.verifiedProperty = verifiedProperty;
    }

    public void setFile(File file) {
        this.file = file;
    }

    public Path createClasspath() {
        this.classPath = new Path(this.getProject());
        return this.classPath;
    }

    public void setClasspath(Path classPath) {
        this.classPath = classPath;
    }

    public void setClasspathRef(Reference ref) {
        this.classPath = (Path)ref.getReferencedObject(this.getProject());
    }

    public void setStorageIdGeneratorClass(String storageIdGeneratorClassName) {
        this.storageIdGeneratorClassName = storageIdGeneratorClassName;
    }

    public void addOldSchemas(OldSchemas oldSchemas) {
        this.oldSchemasList.add(oldSchemas);
    }

    /**
     * @throws BuildException if operation fails
     */
    @Override
    public void execute() {

        // Sanity check
        if (this.file == null)
            throw new BuildException("`file' attribute is required specifying output/verify file");
        final boolean generate;
        switch (this.mode) {
        case MODE_VERIFY:
            generate = false;
            break;
        case MODE_GENERATE:
            generate = true;
            break;
        default:
            throw new BuildException("`mode' attribute must be one of `" + MODE_VERIFY + "' or `" + MODE_GENERATE + "'");
        }
        if (this.packages == null)
            throw new BuildException("`packages' attribute is required specifying packages to scan for Java model classes");
        if (this.classPath == null)
            throw new BuildException("`classpath' attribute is required specifying search path for scanned classes");

        // Create directory containing file
        if (generate && this.file.getParent() != null && !this.file.getParentFile().exists() && !this.file.getParentFile().mkdirs())
            throw new BuildException("error creating directory `" + this.file.getParentFile() + "'");

        // Set up mysterious classloader stuff
        final AntClassLoader loader = this.getProject().createClassLoader(this.classPath);
        final ClassLoader currentLoader = this.getClass().getClassLoader();
        if (currentLoader != null)
            loader.setParent(currentLoader);
        loader.setThreadContextLoader();
        try {

            // Model and field type classes
            final HashSet<Class<?>> modelClasses = new HashSet<>();
            final HashSet<Class<?>> fieldTypeClasses = new HashSet<>();

            // Do package scanning
            if (!this.packages.isEmpty()) {

                // Join list
                final StringBuilder buf = new StringBuilder();
                for (String packageName : this.packages) {
                    if (buf.length() > 0)
                        buf.append(' ');
                    buf.append(packageName);
                }
                final String packageNames = buf.toString();

                // Scan for @JSimpleClass classes
                this.log("scanning for @JSimpleClass annotations in packages: " + packageNames);
                for (String className : new JSimpleDBClassScanner().scanForClasses(packageNames)) {
                    this.log("adding JSimpleDB model class " + className);
                    try {
                        modelClasses.add(Class.forName(className, false, Thread.currentThread().getContextClassLoader()));
                    } catch (ClassNotFoundException e) {
                        throw new BuildException("failed to load class `" + className + "'", e);
                    }
                }

                // Scan for @JFieldType classes
                this.log("scanning for @JFieldType annotations in packages: " + packageNames);
                for (String className : new JSimpleDBFieldTypeScanner().scanForClasses(packageNames)) {
                    this.log("adding JSimpleDB field type class `" + className + "'");
                    try {
                        fieldTypeClasses.add(Class.forName(className, false, Thread.currentThread().getContextClassLoader()));
                    } catch (Exception e) {
                        throw new BuildException("failed to instantiate " + className, e);
                    }
                }
            }

            // Do specific class scanning
            for (String className : this.classes) {

                // Load class
                final Class<?> cl;
                try {
                    cl = Class.forName(className, false, Thread.currentThread().getContextClassLoader());
                } catch (ClassNotFoundException e) {
                    throw new BuildException("failed to load class `" + className + "'", e);
                }

                // Add model classes
                if (cl.isAnnotationPresent(JSimpleClass.class)) {
                    this.log("adding JSimpleDB model " + cl);
                    modelClasses.add(cl);
                }

                // Add field types
                if (cl.isAnnotationPresent(JFieldType.class)) {
                    this.log("adding JSimpleDB field type " + cl);
                    fieldTypeClasses.add(cl);
                }
            }

            // Instantiate StorageIdGenerator
            final StorageIdGenerator storageIdGenerator;
            try {
                storageIdGenerator = Class.forName(this.storageIdGeneratorClassName,
                   false, Thread.currentThread().getContextClassLoader())
                  .asSubclass(StorageIdGenerator.class).getConstructor().newInstance();
            } catch (Exception e) {
                throw new BuildException("failed to instantiate class `" + storageIdGeneratorClassName + "'", e);
            }

            // Set up database
            final Database db = new Database(new SimpleKVDatabase());

            // Instantiate and configure field type classes
            for (Class<?> cl : fieldTypeClasses) {

                // Instantiate field types
                this.log("instantiating " + cl + " as field type instance");
                final FieldType<?> fieldType;
                try {
                    fieldType = this.asFieldTypeClass(cl).getConstructor().newInstance();
                } catch (Exception e) {
                    throw new BuildException("failed to instantiate " + cl.getName(), e);
                }

                // Add field type
                try {
                    db.getFieldTypeRegistry().add(fieldType);
                } catch (Exception e) {
                    throw new BuildException("failed to register custom field type " + cl.getName(), e);
                }
            }

            // Set up factory
            final JSimpleDBFactory factory = new JSimpleDBFactory();
            factory.setDatabase(db);
            factory.setSchemaVersion(1);
            factory.setStorageIdGenerator(storageIdGenerator);
            factory.setModelClasses(modelClasses);

            // Build schema model
            this.log("generating JSimpleDB schema from schema classes");
            final SchemaModel schemaModel;
            try {
                schemaModel = factory.newJSimpleDB().getSchemaModel();
            } catch (Exception e) {
                throw new BuildException("schema generation failed: " + e, e);
            }

            // Record schema model in database
            db.createTransaction(schemaModel, 1, true).commit();

            // Verify or generate
            boolean verified = true;
            if (generate) {

                // Write schema model to file
                this.log("writing JSimpleDB schema to `" + this.file + "'");
                try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(this.file))) {
                    schemaModel.toXML(output, true);
                } catch (IOException e) {
                    throw new BuildException("error writing schema to `" + this.file + "': " + e, e);
                }
            } else {

                // Read file
                this.log("verifying JSimpleDB schema matches `" + this.file + "'");
                final SchemaModel verifyModel;
                try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(this.file))) {
                    verifyModel = SchemaModel.fromXML(input);
                } catch (IOException e) {
                    throw new BuildException("error reading schema from `" + this.file + "': " + e, e);
                }

                // Compare
                final boolean matched = matchNames ? schemaModel.equals(verifyModel) : schemaModel.isCompatibleWith(verifyModel);
                if (!matched)
                    verified = false;
                this.log("schema verification " + (matched ? "succeeded" : "failed"));
                if (!matched)
                    this.log(schemaModel.differencesFrom(verifyModel).toString());
            }

            // Check for conflicts with other schema versions
            if (verified) {
                int schemaVersion = 2;
                for (OldSchemas oldSchemas : this.oldSchemasList) {
                    for (Iterator<?> i = oldSchemas.iterator(); i.hasNext(); ) {
                        final Resource resource = (Resource)i.next();
                        this.log("checking schema for conflicts with " + resource);
                        final SchemaModel otherSchema;
                        try (BufferedInputStream input = new BufferedInputStream(resource.getInputStream())) {
                            otherSchema = SchemaModel.fromXML(input);
                        } catch (IOException e) {
                            throw new BuildException("error reading schema from `" + resource + "': " + e, e);
                        }
                        try {
                            db.createTransaction(otherSchema, schemaVersion++, true).commit();
                        } catch (Exception e) {
                            this.log("schema conflicts with " + resource + ": " + e);
                            verified = false;
                        }
                    }
                }
            }

            // Check verification results
            if (this.verifiedProperty != null)
                this.getProject().setProperty(this.verifiedProperty, "" + verified);
            if (!verified && this.failOnError)
                throw new BuildException("schema verification failed");
        } finally {
            loader.resetThreadContextLoader();
            loader.cleanup();
        }
    }

    @SuppressWarnings("unchecked")
    private Class<? extends FieldType<?>> asFieldTypeClass(Class<?> klass) {
        try {
            return (Class<? extends FieldType<?>>)klass.asSubclass(FieldType.class);
        } catch (ClassCastException e) {
            throw new BuildException("invalid @" + JFieldType.class.getSimpleName() + " annotation on "
              + klass + ": type is not a subclass of " + FieldType.class);
        }
    }
}

