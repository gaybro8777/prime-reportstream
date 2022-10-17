import React from "react";

import HipaaNotice from "../../components/HipaaNotice";
import { useOrgName } from "../../hooks/UseOrgName";
import Title from "../../components/Title";
import { MemberType } from "../../hooks/UseOktaMemberships";
import { AuthElement } from "../../components/AuthElement";
import { BasicHelmet } from "../../components/header/BasicHelmet";
import { withCatchAndSuspense } from "../../components/RSErrorBoundary";

import ReportsTable from "./Table/ReportsTable";

function Daily() {
    const orgName: string = useOrgName();
    return (
        <>
            <BasicHelmet pageTitle="Daily data" />
            <section className="grid-container margin-bottom-5 tablet:margin-top-6">
                <Title preTitle={orgName} title="COVID-19" />
            </section>
            {withCatchAndSuspense(<ReportsTable />)}
            <HipaaNotice />
        </>
    );
}

export const DailyWithAuth = () => (
    <AuthElement element={<Daily />} requiredUserType={MemberType.RECEIVER} />
);
