local P = {module = "AimohPMXPreview", version = 1}

function P.valid(data)
    if type(data) ~= "table" or data.version ~= P.version or type(data.model) ~= "string" then return false end
    if data.model == "" then return data.settings == "" and data.signature == "" end
    return #data.model == 20 and data.model:match("^pmx%-%x+$") ~= nil
        and type(data.signature) == "string" and #data.signature == 64 and data.signature:match("^%x+$") ~= nil
        and type(data.settings) == "string" and #data.settings >= 2 and #data.settings <= 65536
end

return P
