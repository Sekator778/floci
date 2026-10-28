package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * An update that drops an AppSync property clears it, and one the stack never declared keeps the
 * value set outside it. Run through CreateStack and UpdateStack so the previous template's
 * properties, evaluated under its conditions, reach the provisioner the way they do for a user: a
 * {@code Fn::If} that chose {@code AWS::NoValue} on the first template counts as not declared.
 */
@QuarkusTest
class AppSyncCfnRemovalIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/cloudformation/aws4_request";
    private static final String APPSYNC_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/appsync/aws4_request";
    private static final String STACK = "appsync-cfn-removal-it";

    private static final String FIRST = """
        {
          "Conditions": {"Off": {"Fn::Equals": ["a", "b"]}},
          "Resources": {
            "GraphQlApi": {
              "Type": "AWS::AppSync::GraphQLApi",
              "Properties": {
                "Name": "appsync-cfn-removal-it-api",
                "AuthenticationType": "API_KEY",
                "OwnerContact": "platform-team",
                "IntrospectionConfig": {"Fn::If": ["Off", "DISABLED", {"Ref": "AWS::NoValue"}]}
              }
            }
          },
          "Outputs": {"ApiId": {"Value": {"Fn::GetAtt": ["GraphQlApi", "ApiId"]}}}
        }
        """;

    private static final String SECOND = """
        {
          "Resources": {
            "GraphQlApi": {
              "Type": "AWS::AppSync::GraphQLApi",
              "Properties": {
                "Name": "appsync-cfn-removal-it-api",
                "AuthenticationType": "API_KEY"
              }
            }
          },
          "Outputs": {"ApiId": {"Value": {"Fn::GetAtt": ["GraphQlApi", "ApiId"]}}}
        }
        """;

    @Test
    void anUpdateClearsWhatTheNewTemplateDropsAndKeepsWhatTheStackNeverDeclared() {
        cloudFormation("CreateStack", FIRST);
        CfnStackWaits.awaitStatus(STACK, "CREATE_COMPLETE");
        String apiId = outputValue(describeStacks(), "ApiId");

        appSync(apiId)
            .body("graphqlApi.ownerContact", equalTo("platform-team"))
            .body("graphqlApi.introspectionConfig", nullValue());

        // Set outside the stack, which the first template left to AWS::NoValue.
        given().contentType("application/json").header("Authorization", APPSYNC_AUTH)
            .body("{\"name\":\"appsync-cfn-removal-it-api\",\"authenticationType\":\"API_KEY\","
                    + "\"introspectionConfig\":\"DISABLED\"}")
        .when().post("/v1/apis/" + apiId).then().statusCode(200);

        cloudFormation("UpdateStack", SECOND);
        CfnStackWaits.awaitStatus(STACK, "UPDATE_COMPLETE");

        appSync(apiId)
            .body("graphqlApi.ownerContact", nullValue())
            .body("graphqlApi.introspectionConfig", equalTo("DISABLED"));

        cloudFormation("DeleteStack", null);
        CfnStackWaits.awaitStackDeleted(STACK);
    }

    private static void cloudFormation(String action, String template) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", STACK);
        if (template != null) {
            request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String describeStacks() {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackName>" + STACK + "</StackName>"))
            .extract().asString();
    }

    private static String outputValue(String xml, String key) {
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue").get(key);
    }

    private static ValidatableResponse appSync(String apiId) {
        return given().header("Authorization", APPSYNC_AUTH).when().get("/v1/apis/" + apiId).then().statusCode(200);
    }
}
