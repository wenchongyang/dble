package io.mycat.config.loader.zkprocess.parse.entryparse.schema.xml;

import java.io.IOException;

import javax.xml.bind.JAXBException;
import javax.xml.stream.XMLStreamException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.mycat.config.loader.zkprocess.entity.Schemas;
import io.mycat.config.loader.zkprocess.parse.ParseXmlServiceInf;
import io.mycat.config.loader.zkprocess.parse.XmlProcessBase;

/**
 *
 *
 * author:liujun
 * Created:2016/9/16
 *
 *
 *
 *
 */
public class SchemasParseXmlImpl implements ParseXmlServiceInf<Schemas> {


    private static final Logger LOGGER = LoggerFactory.getLogger(SchemasParseXmlImpl.class);

    private XmlProcessBase parseBean;

    public SchemasParseXmlImpl(XmlProcessBase parseBase) {

        this.parseBean = parseBase;
        parseBean.addParseClass(Schemas.class);
    }

    @Override
    public Schemas parseXmlToBean(String path) {

        Schemas schema = null;

        try {
            schema = (Schemas) this.parseBean.baseParseXmlToBean(path);
        } catch (JAXBException e) {
            e.printStackTrace();
            LOGGER.error("SchemasParseXmlImpl parseXmlToBean JAXBException", e);
        } catch (XMLStreamException e) {
            e.printStackTrace();
            LOGGER.error("SchemasParseXmlImpl parseXmlToBean XMLStreamException", e);
        }

        return schema;
    }

    @Override
    public void parseToXmlWrite(Schemas data, String outputFile, String dataName) {
        try {
            this.parseBean.baseParseAndWriteToXml(data, outputFile, dataName);
        } catch (IOException e) {
            e.printStackTrace();
            LOGGER.error("SchemasParseXmlImpl parseToXmlWrite IOException", e);
        }
    }

}
