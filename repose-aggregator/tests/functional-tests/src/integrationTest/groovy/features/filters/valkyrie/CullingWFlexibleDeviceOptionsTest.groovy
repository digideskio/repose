/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package features.filters.valkyrie

import framework.ReposeValveTest
import framework.category.Slow
import framework.mocks.MockIdentityV2Service
import framework.mocks.MockValkyrie
import groovy.json.JsonSlurper
import org.junit.experimental.categories.Category
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.MessageChain
import org.rackspace.deproxy.Response
import spock.lang.Unroll

/**
 * Created by jennyvo on 10/30/15.
 * flexible device id uri - uri null with device-id-mismatch-action
 *      1, fail (default)
 *      2, keep
 *      3, remove
 *
 * Update on 01/28/15
 *  - replace client-auth with keystone-v2
 */
@Category(Slow)
class CullingWFlexibleDeviceOptionsTest extends ReposeValveTest {
    def static originEndpoint
    def static identityEndpoint
    def static valkyrieEndpoint

    def static MockIdentityV2Service fakeIdentityService
    def static MockValkyrie fakeValkyrie
    def static Map params = [:]
    def static deviceId1 = "520707"
    def static deviceId2 = "520708"

    def static random = new Random()
    def static String jsonrespbody = """{
        "values": [
            {
                "id": "en6bShuX7a",
                "label": "brad@morgabra.com",
                "ip_addresses": null,
                "metadata": {
                    "userId": "325742",
                    "email": "brad@morgabra.com"
                },
                "managed": false,
                "uri": "http://core.rackspace.com/accounts/123456/devices/$deviceId1",
                "agent_id": "e333a7d9-6f98-43ea-aed3-52bd06ab929f",
                "active_suppressions": [],
                "scheduled_suppressions": [],
                "created_at": 1405963090100,
                "updated_at": 1409247144717
            },
            {
                "id": "enADqSly1y",
                "label": "test",
                "ip_addresses": null,
                "metadata": null,
                "managed": false,
                "uri": "http://core.rackspace.com/accounts/123456/devices/$deviceId2",
                "agent_id": null,
                "active_suppressions": [],
                "scheduled_suppressions": [],
                "created_at": 1411055897191,
                "updated_at": 1411055897191
            },
            {
                "id": "enADqSly1x",
                "label": "test2",
                "ip_addresses": null,
                "metadata": null,
                "managed": false,
                "uri": null,
                "agent_id": null,
                "active_suppressions": [],
                "scheduled_suppressions": [],
                "created_at": 1411055897191,
                "updated_at": 1411055897191
            },
            {
                "id": "enADqSly1z",
                "label": "test3",
                "ip_addresses": null,
                "metadata": null,
                "managed": false,
                "uri": "foo/bar",
                "agent_id": null,
                "active_suppressions": [],
                "scheduled_suppressions": [],
                "created_at": 1411055897191,
                "updated_at": 1411055897191
            },
            {
                "id": "enADqSly11",
                "label": "test3",
                "ip_addresses": null,
                "metadata": null,
                "managed": false,
                "uri": "http://core.rackspace.com/account/123456",
                "agent_id": null,
                "active_suppressions": [],
                "scheduled_suppressions": [],
                "created_at": 1411055897191,
                "updated_at": 1411055897191
            }
        ],
        "metadata": {
            "count": 4,
            "limit": 4,
            "marker": null,
            "next_marker": "enB11JvqNv",
            "next_href": "https://monitoring.api.rackspacecloud.com/v1.0/731078/entities?limit=2&marker=enB11JvqNv"
        }
    }"""


    def setupSpec() {
        deproxy = new Deproxy()
        reposeLogSearch.cleanLog()

        params = properties.getDefaultTemplateParams()
        repose.configurationProvider.cleanConfigDirectory()
        repose.configurationProvider.applyConfigs("common", params);
        repose.configurationProvider.applyConfigs("features/filters/valkyrie", params);
        repose.configurationProvider.applyConfigs("features/filters/valkyrie/collectionresources", params);
        repose.start()

        originEndpoint = deproxy.addEndpoint(properties.targetPort, 'origin service')
        fakeIdentityService = new MockIdentityV2Service(properties.identityPort, properties.targetPort)
        identityEndpoint = deproxy.addEndpoint(properties.identityPort, 'identity service', null, fakeIdentityService.handler)
        fakeIdentityService.checkTokenValid = true

        fakeValkyrie = new MockValkyrie(properties.valkyriePort)
        valkyrieEndpoint = deproxy.addEndpoint(properties.valkyriePort, 'valkyrie service', null, fakeValkyrie.handler)
    }

    def cleanupSpec() {
        if (deproxy) {
            deproxy.shutdown()
        }

        if (repose) {
            repose.stop()
        }
    }

    @Unroll("Fail default - permission: #permission for #method with tenant: #tenantID and deviceIDs: #deviceID, #deviceID2 should return a #responseCode")
    def "Test device uri mismatch with default (fail)"() {
        given: "a list permission devices defined in Valkyrie"
        fakeIdentityService.with {
            client_token = UUID.randomUUID().toString()
            client_tenantid = tenantID
        }

        fakeValkyrie.with {
            device_id = deviceID
            device_id2 = deviceID2
            device_perm = permission
        }

        "Json Response from origin service"
        def jsonResp = { request -> return new Response(200, "OK", ["content-type": "application/json"], jsonrespbody) }

        when: "a request is made against a device with Valkyrie set permissions"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint + "/resources", method: method,
                headers: [
                        'content-type': 'application/json',
                        'X-Auth-Token': fakeIdentityService.client_token,
                        'x-contact-id': '123456',
                        'x-tenant-id' : tenantID
                ],
                defaultHandler: jsonResp
        )

        then: "check response"
        mc.receivedResponse.code == responseCode

        where:
        method | tenantID       | deviceID | deviceID2 | permission     | responseCode
        "GET"  | randomTenant() | "520707" | "511123"  | "view_product" | "500"
        "GET"  | randomTenant() | "520708" | "511123"  | "view_product" | "500"
        "GET"  | randomTenant() | "520707" | "520708"  | "view_product" | "500"
        "GET"  | randomTenant() | "520705" | "520706"  | "view_product" | "500"
    }


    @Unroll("Fail - permission: #permission for #method with tenant: #tenantID and deviceIDs: #deviceID, #deviceID2 should return a #responseCode")
    def "Test device uri mismatch with fail option"() {
        given: "reconfig repose with null uri fail action"
        def params = properties.getDefaultTemplateParams()
        repose.configurationProvider.cleanConfigDirectory()
        repose.configurationProvider.applyConfigs("common", params);
        repose.configurationProvider.applyConfigs("features/filters/valkyrie", params);
        repose.configurationProvider.applyConfigs("features/filters/valkyrie/collectionresources", params);
        repose.configurationProvider.applyConfigs("features/filters/valkyrie/collectionresources/nullidaction", params);
        repose.start()

        fakeIdentityService.with {
            client_token = UUID.randomUUID().toString()
            client_tenantid = tenantID
        }

        fakeValkyrie.with {
            device_id = deviceID
            device_id2 = deviceID2
            device_perm = permission
        }

        "Json Response from origin service"
        def jsonResp = { request -> return new Response(200, "OK", ["content-type": "application/json"], jsonrespbody) }

        when: "a request is made against a device with Valkyrie set permissions"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint + "/resources", method: method,
                headers: [
                        'content-type': 'application/json',
                        'X-Auth-Token': fakeIdentityService.client_token,
                        'x-contact-id': '123456',
                        'x-tenant-id' : tenantID
                ],
                defaultHandler: jsonResp
        )

        then: "check response"
        mc.receivedResponse.code == responseCode

        where:
        method | tenantID       | deviceID | deviceID2 | permission     | responseCode
        "GET"  | randomTenant() | "520707" | "511123"  | "view_product" | "500"
        "GET"  | randomTenant() | "520708" | "511123"  | "view_product" | "500"
        "GET"  | randomTenant() | "520707" | "520708"  | "view_product" | "500"
        "GET"  | randomTenant() | "520705" | "520706"  | "view_product" | "500"
    }

    @Unroll("Keep - permission: #permission for #method with tenant: #tenantID and deviceIDs: #deviceID, #deviceID2 should return a #responseCode")
    def "Test get match resource list with null uri keep"() {
        given: "reconfig repose with null uri keep action"
        def params = properties.getDefaultTemplateParams()
        repose.configurationProvider.cleanConfigDirectory()
        repose.configurationProvider.applyConfigs("common", params);
        repose.configurationProvider.applyConfigs("features/filters/valkyrie", params);
        repose.configurationProvider.applyConfigs("features/filters/valkyrie/collectionresources", params);
        repose.configurationProvider.applyConfigs("features/filters/valkyrie/collectionresources/nullidaction/keep", params);
        repose.start()

        fakeIdentityService.with {
            client_token = UUID.randomUUID().toString()
            client_tenantid = tenantID
        }

        fakeValkyrie.with {
            device_id = deviceID
            device_id2 = deviceID2
            device_perm = permission
        }

        "Json Response from origin service"
        def jsonResp = { request -> return new Response(200, "OK", ["content-type": "application/json"], jsonrespbody) }

        when: "a request is made against a device with Valkyrie set permissions"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint + "/resources", method: method,
                headers: [
                        'content-type': 'application/json',
                        'X-Auth-Token': fakeIdentityService.client_token,
                        'x-contact-id': '123456',
                        'x-tenant-id' : tenantID
                ],
                defaultHandler: jsonResp
        )

        def body = new String(mc.receivedResponse.body)
        def slurper = new JsonSlurper()
        def result = slurper.parseText(body)

        then: "check response"
        mc.handlings.size() == 1
        mc.receivedResponse.code == responseCode
        result.values.size == size
        result.metadata.count == size

        where:
        method | tenantID       | deviceID | deviceID2 | permission     | responseCode | size
        "GET"  | randomTenant() | "520707" | "511123"  | "view_product" | "200"        | 4
        "GET"  | randomTenant() | "520708" | "511123"  | "view_product" | "200"        | 4
        "GET"  | randomTenant() | "520707" | "520708"  | "view_product" | "200"        | 5
        "GET"  | randomTenant() | "520705" | "520706"  | "view_product" | "200"        | 3
    }

    @Unroll("Remove - permission: #permission for #method with tenant: #tenantID and deviceIDs: #deviceID, #deviceID2 should return a #responseCode")
    def "Test Match Resource list with null uri remove"() {
        given: "reconfig repose with null uri remove action"
        def params = properties.getDefaultTemplateParams()
        repose.configurationProvider.cleanConfigDirectory()
        repose.configurationProvider.applyConfigs("common", params);
        repose.configurationProvider.applyConfigs("features/filters/valkyrie", params);
        repose.configurationProvider.applyConfigs("features/filters/valkyrie/collectionresources", params);
        repose.configurationProvider.applyConfigs("features/filters/valkyrie/collectionresources/nullidaction", params);
        repose.configurationProvider.applyConfigs("features/filters/valkyrie/collectionresources/nullidaction/remove", params);
        repose.start()

        "a list of permission defined in valkyrie"
        fakeIdentityService.with {
            client_token = UUID.randomUUID().toString()
            client_tenantid = tenantID
        }

        fakeValkyrie.with {
            device_id = deviceID
            device_id2 = deviceID2
            device_perm = permission
        }

        "Json Response from origin service"
        def jsonResp = { request -> return new Response(200, "OK", ["content-type": "application/json"], jsonrespbody) }

        when: "a request is made against a device with Valkyrie set permissions"
        MessageChain mc = deproxy.makeRequest(url: reposeEndpoint + "/resources", method: method,
                headers: [
                        'content-type': 'application/json',
                        'X-Auth-Token': fakeIdentityService.client_token,
                        'x-contact-id': '123456',
                        'x-tenant-id' : tenantID
                ],
                defaultHandler: jsonResp
        )

        def body = new String(mc.receivedResponse.body)
        def slurper = new JsonSlurper()
        def result = slurper.parseText(body)

        then: "check response"
        mc.handlings.size() == 1
        mc.receivedResponse.code == responseCode
        result.values.size == size
        result.metadata.count == size

        where:
        method | tenantID       | deviceID | deviceID2 | permission     | responseCode | size
        "GET"  | randomTenant() | "520707" | "511123"  | "view_product" | "200"        | 1
        "GET"  | randomTenant() | "520708" | "511123"  | "view_product" | "200"        | 1
        "GET"  | randomTenant() | "520707" | "520708"  | "view_product" | "200"        | 2
        "GET"  | randomTenant() | "520705" | "520706"  | "view_product" | "200"        | 0
    }

    def String randomTenant() {
        "hybrid:" + random.nextInt()
    }

}
