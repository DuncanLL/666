return function(text,width)
    local manager=getTextManager()
    local font=UIFont.Small
    if width<=0 or manager:MeasureStringX(font,text)<=width then return text end

    local lines={}
    local rest=text
    while manager:MeasureStringX(font,rest)>width do
        local first,last=1,#rest
        while first<last do
            local middle=math.floor((first+last+1)/2)
            if manager:MeasureStringX(font,rest:sub(1,middle))<=width then first=middle
            else last=middle-1 end
        end
        local nextCode=rest:byte(first+1)
        if nextCode and nextCode>=56320 and nextCode<=57343 then
            first=first>1 and first-1 or 2
        end
        local prefix=rest:sub(1,first)
        if not rest:sub(first+1,first+1):match("%s") then
            local space=prefix:match(".*()%s+%S*$")
            if space and space>first/2 then first=space-1 end
        end
        lines[#lines+1]=rest:sub(1,first)
        rest=rest:sub(first+1):gsub("^%s+","")
    end
    lines[#lines+1]=rest
    return table.concat(lines,"\n")
end
