package ru.ymlstudio

import java.nio.file.Files
import java.nio.file.Path
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlin.test.*

class RepositoryTest {
    @Test fun bundledDictionariesExactlyMatchAllProvidedXmlRows() {
        val files = mapOf("country" to "oksmList.xml", "okei" to "okeiList.xml", "unit" to "unitList.xml",
            "category" to "categoryList.xml", "region" to "regionList.xml", "vat" to "ndsList.xml",
            "currency" to "currencyList.xml", "package" to "packageList.xml")
        for ((key, filename) in files) {
            val doc = parse(Path.of("../dictionaries", filename).readText())
            val children = doc.documentElement.childNodes
            val expected = (0 until children.length).map { children.item(it) }.filterIsInstance<org.w3c.dom.Element>().map {
                DictionaryEntry(it.getAttribute("id"), it.getAttribute("code"), it.getAttribute("name").replace('\t', ' ').replace('\n', ' ').replace('\r', ' '), it.getAttribute("parentId"))
            }
            assertEquals(expected, Dictionaries.entries(key), filename)
        }
    }
    @Test fun exportMatchesAcceptedCatalogDeliveryCountryAndCategory() {
        val accepted = parse(fixture("accepted-rls.yml"))
        val template = defaultTemplate()
        val reference = accepted.getElementsByTagNameNS(YML_NAMESPACE, "offer").item(0) as org.w3c.dom.Element
        val values = template.fields.filter { it.target != "param" }.associate { f ->
            f.id to when (f.target) {
                "id" -> reference.getAttribute("id")
                "okei" -> (reference.getElementsByTagNameNS(YML_NAMESPACE, "okei").item(0) as org.w3c.dom.Element).getAttribute("id")
                "regions" -> "Москва; Московская область"
                "deliveryCost" -> "0"
                "deliveryDays" -> "1-30"
                else -> reference.getElementsByTagNameNS(YML_NAMESPACE, f.target).item(0)?.textContent.orEmpty()
            }
        }
        val project = validProject().copy(settings = Settings("RLS", "ИП Лакин Сергей Анатольевич", useVat = true, usePortalCategories = true), templates = listOf(template), products = listOf(Product("test", template.id, values, listOf(Picture("https://example.ru/shirt.png")))))
        val generated = parse(buildYml(project))
        for (tag in listOf("regions", "delivery-options", "oksm", "okei", "ppCategory", "price", "vat")) {
            assertEquals(canonical(accepted.getElementsByTagNameNS(YML_NAMESPACE, tag).item(0)), canonical(generated.getElementsByTagNameNS(YML_NAMESPACE, tag).item(0)), tag)
        }
        val offer = generated.getElementsByTagNameNS(YML_NAMESPACE, "offer").item(0)
        val directNames = (0 until offer.childNodes.length).map { offer.childNodes.item(it) }.filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }.map { it.localName }
        assertFalse("region" in directNames)
        assertFalse("categoryId" in directNames)
    }
    @Test fun allEmittedOfferElementsAreDeclaredByOfficialSchema() {
        val source = parse(fixture("xsd/schema.xsd"))
        val xsd = "http://www.w3.org/2001/XMLSchema"
        val types = source.getElementsByTagNameNS(xsd, "complexType")
        val offerType = (0 until types.length).map { types.item(it) as org.w3c.dom.Element }.single { it.getAttribute("name") == "offerType" }
        val declarations = offerType.getElementsByTagNameNS(xsd, "element")
        val allowed = (0 until declarations.length).map { (declarations.item(it) as org.w3c.dom.Element).getAttribute("name") }.toSet()
        val template = Template("all", "Все поля", fields = fieldDefinitions.map { Field(it.target, it.target, it.label, it.type) })
        val values = fieldDefinitions.associate { it.target to when(it.target) { "regions" -> "Москва"; "deliveryCost" -> "0"; "deliveryDays" -> "1-30"; "oksm" -> "156"; else -> "1" } }
        val p = validProject().copy(templates = listOf(template), products = listOf(Product("all", "all", values)))
        val doc = parse(buildYml(p, allowInvalid = true))
        val offer = doc.getElementsByTagNameNS(YML_NAMESPACE, "offer").item(0)
        val children = (0 until offer.childNodes.length).map { offer.childNodes.item(it) }.filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
        assertTrue(children.all { it.localName in allowed }, children.map { it.localName }.toString())
        assertEquals("regions", doc.getElementsByTagNameNS(YML_NAMESPACE, "region").item(0).parentNode.localName)
    }

    @Test fun downloadedXsdRequiresNonemptyCategoriesBlock() {
        val doc = parse(fixture("xsd/schema.xsd"))
        val elements = doc.getElementsByTagNameNS("http://www.w3.org/2001/XMLSchema", "element")
        val categoryBlock = (0 until elements.length).map { elements.item(it) as org.w3c.dom.Element }.single { it.getAttribute("name") == "categories" }
        assertTrue(categoryBlock.getAttribute("minOccurs") in listOf("", "1"))
        val child = categoryBlock.getElementsByTagNameNS("http://www.w3.org/2001/XMLSchema", "element").item(0) as org.w3c.dom.Element
        assertEquals("category", child.getAttribute("name"))
        assertEquals("1", child.getAttribute("minOccurs"))
    }

    @Test fun vatValuesValidateAgainstIsolatedOfficialNdsType() {
        val source = parse(fixture("xsd/schema.xsd"))
        val ns = "http://www.w3.org/2001/XMLSchema"
        val types = source.getElementsByTagNameNS(ns, "simpleType")
        val nds = (0 until types.length).map { types.item(it) as org.w3c.dom.Element }.single { it.getAttribute("name") == "ndsType" }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().newDocument()
        val root = doc.createElementNS(ns, "xsd:schema")
        root.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:xsd", ns)
        root.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:y", YML_NAMESPACE)
        root.setAttribute("targetNamespace", YML_NAMESPACE)
        root.setAttribute("elementFormDefault", "qualified")
        doc.appendChild(root)
        root.appendChild(doc.importNode(nds, true))
        val element = doc.createElementNS(ns, "xsd:element")
        element.setAttribute("name", "vat"); element.setAttribute("type", "y:ndsType")
        root.appendChild(element)
        val factory = javax.xml.validation.SchemaFactory.newInstance(ns)
        factory.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setProperty(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        val schema = factory.newSchema(javax.xml.transform.dom.DOMSource(doc))
        fun validateVat(value: String) {
            schema.newValidator().validate(javax.xml.transform.stream.StreamSource("<vat xmlns=\"$YML_NAMESPACE\">$value</vat>".reader()))
        }
        vatAliases.keys.forEach(::validateVat)
        assertFailsWith<org.xml.sax.SAXException> { validateVat("24") }
        assertFailsWith<org.xml.sax.SAXException> { validateVat("VAT_24") }
        val p = validProject().let { it.copy(settings = it.settings.copy(useVat = true), products = it.products.map { product -> product.copy(values = product.values + ("vat" to "5")) }) }
        val vat = parse(buildYml(p)).getElementsByTagNameNS(YML_NAMESPACE, "vat").item(0)
        validateVat(vat.textContent)
    }
    @Test fun vatListExactlyMatchesDownloadedXsdEnumeration() {
        val schema = parse(fixture("xsd/schema.xsd"))
        val ns = "http://www.w3.org/2001/XMLSchema"
        val types = schema.getElementsByTagNameNS(ns, "simpleType")
        val nds = (0 until types.length).map { types.item(it) as org.w3c.dom.Element }.single { it.getAttribute("name") == "ndsType" }
        val enums = nds.getElementsByTagNameNS(ns, "enumeration")
        val values = (0 until enums.length).map { (enums.item(it) as org.w3c.dom.Element).getAttribute("value") }.toSet()
        assertEquals(values, vatAliases.keys)
        assertTrue(vatOptions.all { it.first in values })
        assertFalse("24" in values)
    }

    @Test fun generatedOffersAreRecognizedWithPortalNamespaceAndLocalCategoryMapping() {
        val p = validProject().let { it.copy(products = it.products + it.products.single().copy(id = "second", values = it.products.single().values + ("id" to "A002"))) }
        val document = parse(buildYml(p))
        val root = document.documentElement
        assertEquals("yml_catalog", root.localName)
        assertEquals(YML_NAMESPACE, root.namespaceURI)
        val children = (0 until root.childNodes.length).map { root.childNodes.item(it) }.filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
        assertEquals(listOf("shop", "categories", "offers"), children.map { it.localName })
        val offers = document.getElementsByTagNameNS(YML_NAMESPACE, "offer")
        assertEquals(2, offers.length)
        assertEquals(listOf("A001", "A002"), (0 until offers.length).map { (offers.item(it) as org.w3c.dom.Element).getAttribute("id") })
        assertEquals("offers", offers.item(0).parentNode.localName)
        assertEquals(0, document.getElementsByTagNameNS(YML_NAMESPACE, "categoryId").length)
        assertEquals(2, document.getElementsByTagNameNS(YML_NAMESPACE, "ppCategory").length)
        assertEquals(2, document.getElementsByTagNameNS(YML_NAMESPACE, "oksm").length)
        assertEquals(2, document.getElementsByTagNameNS(YML_NAMESPACE, "delivery").length)
        assertEquals(8, document.getElementsByTagNameNS(YML_NAMESPACE, "param").length)
        assertEquals("RUB", (document.getElementsByTagNameNS(YML_NAMESPACE, "currency").item(0) as org.w3c.dom.Element).getAttribute("id"))
        assertEquals("RUB", document.getElementsByTagNameNS(YML_NAMESPACE, "currencyId").item(0).textContent)
    }

    @Test fun forcedExportWritesInvalidDraftAndDoesNotNeedPhotosForPlainYml() = temporary { dir ->
        ProjectRepository(dir.resolve("data")).use { repo ->
            val draft = validProject().let { it.copy(products = it.products.map { p -> p.copy(values = p.values + ("price" to "wrong"), pictures = listOf(Picture(file = "a".repeat(32) + ".png"))) }) }
            val target = dir.resolve("draft.yml")
            target.writeText("previous")
            assertFails { repo.export(draft, target, false) }
            assertEquals("previous", target.readText())
            repo.export(draft, target, false, allowInvalid = true)
            assertEquals("wrong", parse(target.readText()).getElementsByTagName("price").item(0).textContent)
            val zip = dir.resolve("draft.zip")
            repo.export(draft.copy(products = draft.products.map { it.copy(pictures = emptyList()) }), zip, true, allowInvalid = true)
            ZipFile(zip.toFile()).use { archive -> assertNotNull(archive.getEntry("catalog.yml")) }; Unit
        }
    }

    private fun <T> temporary(block: (Path) -> T): T {
        val path = Files.createTempDirectory("yml-studio-test")
        return try { block(path) } finally { path.toFile().deleteRecursively() }
    }
    private fun fixture(name: String) = javaClass.getResource("/$name")!!.readText()
    private fun parse(text: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(text.byteInputStream())
    private fun canonical(node: org.w3c.dom.Node): String = when (node.nodeType) {
        org.w3c.dom.Node.ELEMENT_NODE -> node.nodeName + "[" + (0 until node.attributes.length).map { node.attributes.item(it) }.sortedBy { it.nodeName }.joinToString { it.nodeName + "=" + it.nodeValue } + "](" + (0 until node.childNodes.length).joinToString("") { canonical(node.childNodes.item(it)) } + ")"
        org.w3c.dom.Node.TEXT_NODE -> node.nodeValue.takeIf { it.isNotBlank() }.orEmpty()
        else -> ""
    }
    @Test fun legacyJsonAndXmlParity() {
        val p = projectJson.decodeFromString<Project>(fixture("legacy-project.json"))
        val upgraded = p.withMinimalPreset()
        assertEquals(p.products, upgraded.products)
        assertTrue(validate(upgraded).errors.any { "Регионы поставки" in it })
        assertTrue(validate(upgraded).errors.any { "Стоимость доставки" in it })
        assertFailsWith<IllegalArgumentException> { buildYml(upgraded) }
        val forced = parse(buildYml(upgraded, allowInvalid = true))
        assertEquals("A001", (forced.getElementsByTagNameNS(YML_NAMESPACE, "offer").item(0) as org.w3c.dom.Element).getAttribute("id"))
        assertEquals("643", (forced.getElementsByTagNameNS(YML_NAMESPACE, "oksm").item(0) as org.w3c.dom.Element).getAttribute("code"))
    }
    @Test fun persistenceAndInvalidWriteLeavesExistingFile() = temporary { dir ->
        ProjectRepository(dir).use { repo ->
            val p = validProject(); repo.save(p); assertEquals(p, repo.load())
            assertFailsWith<IllegalArgumentException> { repo.save(p.copy(products = p.products.map { it.copy(templateId = "missing") })) }
            assertEquals(p, repo.load())
        }
    }
    @Test fun corruptProjectIsNotSilentlyReplaced() = temporary { dir ->
        dir.resolve("project.json").writeText("{broken")
        ProjectRepository(dir).use { assertFails { it.load() } }
        assertEquals("{broken", dir.resolve("project.json").readText())
    }
    @Test fun onlyOneWriterAndLockReleased() = temporary { dir ->
        ProjectRepository(dir).use { assertFails { ProjectRepository(dir) } }
        ProjectRepository(dir).use { assertEquals(defaultProject(), it.load()) }
    }
    @Test fun uploadValidationAndBundle() = temporary { dir ->
        val source = dir.resolve("source.png")
        ImageIO.write(BufferedImage(600, 700, BufferedImage.TYPE_INT_RGB), "png", source.toFile())
        ProjectRepository(dir.resolve("data")).use { repo ->
            val pic = repo.addImage(source); assertEquals(600, pic.width); assertEquals(700, pic.height)
            val p = validProject().let { it.copy(products = it.products.map { product -> product.copy(pictures = listOf(pic)) }) }; repo.save(p)
            val zip = dir.resolve("export.zip"); repo.export(p, zip, true)
            ZipFile(zip.toFile()).use { assertNotNull(it.getEntry("catalog.yml")); assertNotNull(it.getEntry("images/${pic.file}")) }
            ImageIO.write(BufferedImage(249, 600, BufferedImage.TYPE_INT_RGB), "png", source.toFile())
            assertFailsWith<IllegalArgumentException> { repo.addImage(source) }
            source.writeText("not an image"); assertFailsWith<IllegalArgumentException> { repo.addImage(source) }
            assertFailsWith<IllegalArgumentException> { repo.export(p, repo.directory.resolve("project.json"), false) }
            assertEquals(p, repo.load())
        }
    }
    @Test fun importBackupAndMissingImages() = temporary { dir ->
        val old = dir.resolve("old").createDirectories(); val source = old.resolve("project.json")
        val p = projectJson.decodeFromString<Project>(fixture("legacy-project.json")); source.writeText(fixture("legacy-project.json"))
        ProjectRepository(dir.resolve("new")).use { repo ->
            repo.save(defaultProject()); assertEquals(p, repo.importProject(source)); assertEquals(p, repo.load())
            val backups = repo.directory.listDirectoryEntries("backup-before-import-*.zip"); assertEquals(1, backups.size)
            ZipFile(backups[0].toFile()).use { zip -> assertEquals(defaultProject(), projectJson.decodeFromString<Project>(zip.getInputStream(zip.getEntry("project.json")).reader().readText())) }
            source.writeText(projectJson.encodeToString(Project.serializer(), p.copy(products = p.products.map { it.copy(pictures = listOf(Picture(file = "a".repeat(32) + ".png"))) })))
            assertFailsWith<IllegalArgumentException> { repo.importProject(source) }; assertEquals(p, repo.load())
        }
    }
    @Test fun missingImageDoesNotReplaceExport() = temporary { dir ->
        ProjectRepository(dir.resolve("data")).use { repo ->
            val p = validProject().let { it.copy(products = it.products.map { p -> p.copy(pictures = listOf(Picture(file = "a".repeat(32) + ".png", width = 600, height = 600))) }) }
            val target = dir.resolve("export.zip"); target.writeText("keep")
            assertFails { repo.export(p, target, true) }; assertEquals("keep", target.readText())
        }
    }
}
