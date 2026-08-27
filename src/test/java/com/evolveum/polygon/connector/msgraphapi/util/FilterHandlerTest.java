package com.evolveum.polygon.connector.msgraphapi.util;

import org.identityconnectors.framework.common.objects.AttributeBuilder;
import org.identityconnectors.framework.common.objects.ObjectClass;
import org.identityconnectors.framework.common.objects.filter.Filter;
import org.identityconnectors.framework.common.objects.filter.FilterBuilder;
import org.testng.annotations.Test;

import static org.testng.AssertJUnit.assertEquals;

@Test(groups = "unit")
public class FilterHandlerTest {

    private ResourceQuery translate(Filter filter) {
        return filter.accept(
                new FilterHandler(),
                new ResourceQuery(ObjectClass.ACCOUNT, "id", "userPrincipalName"));
    }

    @Test
    public void testBooleanFalseIsRenderedAsODataLiteral() {
        ResourceQuery query = translate(FilterBuilder.equalTo(
                AttributeBuilder.build("onPremisesSyncEnabled", false)));

        assertEquals("$filter=onPremisesSyncEnabled eq false", query.toString());
    }

    @Test
    public void testBooleanTrueIsRenderedAsODataLiteral() {
        ResourceQuery query = translate(FilterBuilder.equalTo(
                AttributeBuilder.build("accountEnabled", true)));

        assertEquals("$filter=accountEnabled eq true", query.toString());
    }

    @Test
    public void testBooleanStringOnBooleanAttributeIsRenderedAsODataLiteral() {
        ResourceQuery query = translate(FilterBuilder.equalTo(
                AttributeBuilder.build("onPremisesSyncEnabled", "false")));

        assertEquals("$filter=onPremisesSyncEnabled eq false", query.toString());
    }

    @Test
    public void testEmptyValueIsRenderedAsNullLiteral() {
        ResourceQuery query = translate(FilterBuilder.equalTo(
                AttributeBuilder.build("onPremisesSyncEnabled")));

        assertEquals("$filter=onPremisesSyncEnabled eq null&$count=true", query.toString());
    }

    @Test
    public void testNullStringOnNullableAttributeIsRenderedAsNullLiteral() {
        ResourceQuery query = translate(FilterBuilder.equalTo(
                AttributeBuilder.build("onPremisesSyncEnabled", "null")));

        assertEquals("$filter=onPremisesSyncEnabled eq null&$count=true", query.toString());
    }

    @Test
    public void testRegularStringStillQuotedAndEscaped() {
        ResourceQuery query = translate(FilterBuilder.equalTo(
                AttributeBuilder.build("surname", "O'Connor")));

        assertEquals("$filter=surname eq 'O''Connor'", query.toString());
    }
}
